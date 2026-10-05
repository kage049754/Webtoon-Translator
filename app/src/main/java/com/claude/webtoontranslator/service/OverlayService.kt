package com.claude.webtoontranslator.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.claude.webtoontranslator.MainActivity
import com.claude.webtoontranslator.R
import com.claude.webtoontranslator.ocr.OnlineTranslationManager
import com.claude.webtoontranslator.ocr.TextBlockResult
import com.claude.webtoontranslator.ocr.TextRecognitionManager
import com.claude.webtoontranslator.ocr.TranslationManager
import com.claude.webtoontranslator.ocr.GeminiTranslationManager
import com.claude.webtoontranslator.util.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val CHANNEL_ID = "overlay_translator_channel"
        const val NOTIFICATION_ID = 1001

        private const val CLICK_DRAG_THRESHOLD = 12

        // Five quick taps completely closes the service.
        private const val CLOSE_TAP_COUNT = 5
        private const val TAP_RESET_DELAY = 1800L
    }

    /*
     * Button cycle:
     *
     * START  -> ▶
     * READY  -> 🔍
     * SCAN   -> …
     * RESULT -> ⏹
     */
    private enum class State {
        START,
        READY,
        WORKING,
        SHOWING
    }

    private lateinit var windowManager: WindowManager

    private var mediaProjection: MediaProjection? = null
    private var captureManager: ScreenCaptureManager? = null

    private val textRecognitionManager =
        TextRecognitionManager()

    private val translationManager =
        TranslationManager()

    private val onlineTranslationManager =
        OnlineTranslationManager()

    private val geminiTranslationManager =
        GeminiTranslationManager()

    private lateinit var settingsDataStore: SettingsDataStore

    private val serviceScope =
        CoroutineScope(
            Dispatchers.Main + Job()
        )

    private var buttonView: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null

    private var overlayView: TranslationOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var areaSelectorView: ScanAreaSelectorView? = null
    private var areaSelectorParams: WindowManager.LayoutParams? = null

    private var state = State.READY

    private var tapCount = 0
    private var lastFrameSignature: IntArray? = null
    private var lastFrameWidth = 0
    private var lastFrameHeight = 0
    private var lastOverlayItems: List<OverlayItem> = emptyList()

    private val tapResetHandler =
        Handler(Looper.getMainLooper())

    private val resetTapCountRunnable =
        Runnable {
            tapCount = 0
        }

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        settingsDataStore =
            SettingsDataStore(this)

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            foregroundServiceType()
        )

        val resultCode =
            intent?.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            ) ?: Activity.RESULT_CANCELED

        val resultData: Intent? =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )

            } else {

                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(
                    EXTRA_RESULT_DATA
                )
            }

        if (
            resultCode != Activity.RESULT_OK ||
            resultData == null
        ) {

            Toast.makeText(
                this,
                "Screen capture permission data is missing. Tap Start Overlay again and approve screen capture.",
                Toast.LENGTH_LONG
            ).show()

            stopSelf()

            return START_NOT_STICKY
        }

        val projectionManager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val projection = try {

            projectionManager.getMediaProjection(
                resultCode,
                resultData
            )

        } catch (e: SecurityException) {

            Toast.makeText(
                this,
                "Screen capture permission expired. Tap Start Overlay again and approve screen capture.",
                Toast.LENGTH_LONG
            ).show()

            stopSelf()

            return START_NOT_STICKY

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Couldn't start screen capture: ${e.message}",
                Toast.LENGTH_LONG
            ).show()

            stopSelf()

            return START_NOT_STICKY
        }

        if (projection == null) {

            Toast.makeText(
                this,
                "Screen capture could not be started. Tap Start Overlay again and approve screen capture.",
                Toast.LENGTH_LONG
            ).show()

            stopSelf()

            return START_NOT_STICKY
        }

        mediaProjection = projection

        try {

            addButtonOverlay()

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Couldn't draw the floating button: ${e.message}. Check \"Display over other apps\" is enabled for this app.",
                Toast.LENGTH_LONG
            ).show()

            stopSelf()

            return START_NOT_STICKY
        }

        try {

            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {

                    override fun onStop() {
                        stopSelf()
                    }

                },
                Handler(Looper.getMainLooper())
            )

            val metrics =
                DisplayMetrics()

            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
                .getRealMetrics(metrics)

            mediaProjection?.let { projectionInstance ->

                val capture =
                    ScreenCaptureManager(
                        projectionInstance,
                        metrics
                    )

                capture.start()

                captureManager = capture
            }

        } catch (e: Exception) {

            setButtonLabel("!")

            Toast.makeText(
                this,
                "Screen capture setup failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }

        serviceScope.launch {

            try {

                translationManager
                    .preDownloadModels()

                settingsDataStore
                    .setModelsDownloaded(true)

            } catch (_: Exception) {
            }
        }

       setButtonLabel("🔍")
state = State.READY

        return START_NOT_STICKY
    }

    private fun foregroundServiceType(): Int {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            ServiceInfo
                .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

        } else {

            0
        }
    }

    // =========================================================
    // FLOATING BUTTON
    // =========================================================

    private fun addButtonOverlay() {

        if (buttonView != null) {
            return
        }

        val button =
            TextView(this).apply {

                text = "🔍"

                setTextColor(Color.WHITE)

                textSize = 20f

                gravity = Gravity.CENTER

                val background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.OVAL

                        setColor(
                            Color.parseColor(
                                "#6750A4"
                            )
                        )
                    }

                this.background = background

                alpha = 0.95f

                elevation = 12f
            }

        val sizePx =
            (
                56 *
                    resources
                        .displayMetrics
                        .density
                ).toInt()

        val overlayType =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY

            } else {

                @Suppress("DEPRECATION")

                WindowManager.LayoutParams
                    .TYPE_PHONE
            }

        val params =
            WindowManager.LayoutParams(
                sizePx,
                sizePx,
                overlayType,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {

                gravity =
                    Gravity.TOP or
                        Gravity.START

                x = 0
                y = 300
            }

        var downX = 0f
        var downY = 0f

        var startX = 0
        var startY = 0

        var isDrag = false

        button.setOnTouchListener { _, event ->

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    downX =
                        event.rawX

                    downY =
                        event.rawY

                    startX =
                        params.x

                    startY =
                        params.y

                    isDrag = false

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    val dx =
                        (
                            event.rawX -
                                downX
                            ).toInt()

                    val dy =
                        (
                            event.rawY -
                                downY
                            ).toInt()

                    if (
                        abs(dx) >
                        CLICK_DRAG_THRESHOLD ||
                        abs(dy) >
                        CLICK_DRAG_THRESHOLD
                    ) {

                        isDrag = true

                        params.x =
                            startX + dx

                        params.y =
                            startY + dy

                        try {

                            windowManager
                                .updateViewLayout(
                                    button,
                                    params
                                )

                        } catch (_: Exception) {
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {

                    if (!isDrag) {
                        onButtonTapped()
                    }

                    true
                }

                else -> false
            }
        }

        windowManager.addView(
            button,
            params
        )

        buttonView = button
        buttonParams = params
    }

   private fun onButtonTapped() {
    tapCount++

    tapResetHandler.removeCallbacksAndMessages(null)
    tapResetHandler.postDelayed({
        tapCount = 0
    }, TAP_RESET_DELAY)

    /*
     * Five quick taps:
     * Completely close the overlay service.
     */
    if (tapCount >= CLOSE_TAP_COUNT) {
        Toast.makeText(
            this,
            "Closing Webtoon Translator",
            Toast.LENGTH_SHORT
        ).show()

        stopSelf()
        return
    }

    when (state) {

        State.START -> {
            /*
             * If START is reached for any reason,
             * immediately switch to Scan mode.
             */
            state = State.READY
            setButtonLabel("🔍")
        }

        State.READY -> {
            /*
             * Scan immediately.
             */
            runCaptureAndTranslate()
        }

        State.WORKING -> {
            /*
             * Do nothing while OCR/
             * translation is running.
             */
        }

        State.SHOWING -> {
            /*
             * Stop/clear translation,
             * then immediately return to Scan mode.
             */
            clearOverlay()

            state = State.READY
                setButtonLabel("🔍")
        }
    }
}

    // =========================================================
    // CAPTURE + SCAN AREA
    // =========================================================

    private fun runCaptureAndTranslate() {

        val capture =
            captureManager ?: return

        state = State.WORKING

        setButtonLabel("…")

        serviceScope.launch {

            try {

                val fullBitmap =
                    capture.captureFrame()

                if (fullBitmap == null) {

                    setButtonLabel("!")
                    state = State.READY

                    return@launch
                }

                /*
                 * Check the selected scan mode.
                 */
                val scanMode =
                    settingsDataStore
                        .scanMode
                        .first()

                if (
                    scanMode ==
                    "select_area"
                ) {

                    /*
                     * User selected "Select Area".
                     * Show selector first.
                     */
                    state = State.READY

                    setButtonLabel("🔍")

                    showAreaSelector(
                        fullBitmap
                    )

                    return@launch
                }

                val scanData =
                    prepareScanBitmap(
                        fullBitmap,
                        scanMode
                    )

                if (scanData == null) {

                    state = State.READY
                    setButtonLabel("🔍")

                    Toast.makeText(
                        this@OverlayService,
                        "No saved scan area. Select an area first.",
                        Toast.LENGTH_LONG
                    ).show()

                    return@launch
                }

                processBitmap(
                    scanData.bitmap,
                    scanData.offsetX,
                    scanData.offsetY,
                    fullBitmap
                )

            } catch (e: Exception) {

                setButtonLabel("!")
                state = State.READY

                Toast.makeText(
                    this@OverlayService,
                    "Scan failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private data class ScanBitmapData(
        val bitmap: Bitmap,
        val offsetX: Int,
        val offsetY: Int
    )

    private suspend fun prepareScanBitmap(
        fullBitmap: Bitmap,
        mode: String
    ): ScanBitmapData? {

        if (
            mode ==
            "whole_screen"
        ) {

            return ScanBitmapData(
                bitmap = fullBitmap,
                offsetX = 0,
                offsetY = 0
            )
        }

        /*
         * Use the last saved area.
         */
        val hasArea =
            settingsDataStore
                .hasSavedArea
                .first()

        if (!hasArea) {
            return null
        }

        val left =
            settingsDataStore
                .areaLeft
                .first()

        val top =
            settingsDataStore
                .areaTop
                .first()

        val right =
            settingsDataStore
                .areaRight
                .first()

        val bottom =
            settingsDataStore
                .areaBottom
                .first()

        val safeLeft =
            left.coerceIn(
                0,
                fullBitmap.width - 1
            )

        val safeTop =
            top.coerceIn(
                0,
                fullBitmap.height - 1
            )

        val safeRight =
            right.coerceIn(
                safeLeft + 1,
                fullBitmap.width
            )

        val safeBottom =
            bottom.coerceIn(
                safeTop + 1,
                fullBitmap.height
            )

        val width =
            safeRight - safeLeft

        val height =
            safeBottom - safeTop

        if (
            width < 10 ||
            height < 10
        ) {
            return null
        }

        val cropped =
            Bitmap.createBitmap(
                fullBitmap,
                safeLeft,
                safeTop,
                width,
                height
            )

        return ScanBitmapData(
            bitmap = cropped,
            offsetX = safeLeft,
            offsetY = safeTop
        )
    }

    private suspend fun processBitmap(
        bitmap: Bitmap,
        offsetX: Int,
        offsetY: Int,
        fullBitmap: Bitmap
    ) {

        val performanceMode = settingsDataStore.performanceMode.first()
        val change = detectChangedRegion(bitmap, performanceMode)

        if (change.isUnchanged && lastOverlayItems.isNotEmpty()) {
            showOverlay(lastOverlayItems)
            setButtonLabel("⏹")
            state = State.SHOWING
            return
        }

        val scanRect = change.region
        val ocrBitmap = if (scanRect != null &&
            (scanRect.left > 0 || scanRect.top > 0 ||
                scanRect.right < bitmap.width || scanRect.bottom < bitmap.height)
        ) {
            Bitmap.createBitmap(bitmap, scanRect.left, scanRect.top, scanRect.width(), scanRect.height())
        } else {
            bitmap
        }

        val blocks =
            withContext(Dispatchers.Default) {
                textRecognitionManager.recognize(ocrBitmap, performanceMode)
            }

        if (blocks.isEmpty()) {

            setButtonLabel("∅")
            state = State.READY

            return
        }

        /*
         * Move OCR coordinates back to the
         * original full-screen coordinates.
         */
        val adjustedBlocks =
            blocks.map { block ->

                val adjustedRect =
                    Rect(
                        block.boundingBox
                    )

                adjustedRect.offset(
                    offsetX + (scanRect?.left ?: 0),
                    offsetY + (scanRect?.top ?: 0)
                )

                TextBlockResult(
                    text = block.text,
                    boundingBox =
                        adjustedRect
                )
            }

        val mode =
            settingsDataStore
                .translationMode
                .first()

        val changedFullRect = scanRect?.let { Rect(it).apply { offset(offsetX, offsetY) } }
        val retained = if (changedFullRect != null) {
            lastOverlayItems.filter { !Rect.intersects(it.box, changedFullRect) }
        } else emptyList()

        val newOverlayItems =
            if (
                mode ==
                "online"
            ) {

                val targetLang =
                    settingsDataStore
                        .onlineTargetLanguage
                        .first()

                buildOnlineOverlayItems(
                    adjustedBlocks,
                    fullBitmap,
                    targetLang
                )

            } else {

                buildOfflineOverlayItems(
                    adjustedBlocks,
                    fullBitmap
                )
            }

        val overlayItems =
            (retained + newOverlayItems)
                .distinctBy { it.box.toString() + ":" + it.translatedText }

        updateFrameCache(bitmap, overlayItems, performanceMode)

        if (
            overlayItems.isEmpty()
        ) {

            setButtonLabel(
                if (
                    mode ==
                    "online"
                ) {
                    "N/A"
                } else {
                    "EN?"
                }
            )

            state = State.READY

            return
        }

        showOverlay(
            overlayItems
        )

        setButtonLabel("⏹")

        state = State.SHOWING
    }

    private data class ChangeResult(val isUnchanged: Boolean, val region: Rect?)

    private fun detectChangedRegion(bitmap: Bitmap, performanceMode: String): ChangeResult {
        val grid = if (performanceMode == "fast") 24 else 32
        val signature = IntArray(grid * grid)
        for (y in 0 until grid) {
            val py = ((y + 0.5f) * bitmap.height / grid).toInt().coerceIn(0, bitmap.height - 1)
            for (x in 0 until grid) {
                val px = ((x + 0.5f) * bitmap.width / grid).toInt().coerceIn(0, bitmap.width - 1)
                signature[y * grid + x] = bitmap.getPixel(px, py)
            }
        }
        val old = lastFrameSignature
        if (old != null && lastFrameWidth == bitmap.width && lastFrameHeight == bitmap.height && old.contentEquals(signature)) {
            return ChangeResult(true, null)
        }
        if (old == null || lastFrameWidth != bitmap.width || lastFrameHeight != bitmap.height || old.size != signature.size) {
            return ChangeResult(false, Rect(0, 0, bitmap.width, bitmap.height))
        }
        var minX = grid; var minY = grid; var maxX = -1; var maxY = -1; var changed = 0
        for (i in signature.indices) {
            val a = old[i]; val b = signature[i]
            val distance = kotlin.math.abs(Color.red(a) - Color.red(b)) +
                kotlin.math.abs(Color.green(a) - Color.green(b)) +
                kotlin.math.abs(Color.blue(a) - Color.blue(b))
            if (distance > 45) {
                val x = i % grid; val y = i / grid
                minX = minOf(minX, x); minY = minOf(minY, y)
                maxX = maxOf(maxX, x); maxY = maxOf(maxY, y); changed++
            }
        }
        if (changed == 0) return ChangeResult(true, null)
        if (changed.toFloat() / signature.size >= 0.65f) {
            return ChangeResult(false, Rect(0, 0, bitmap.width, bitmap.height))
        }
        val left = (maxOf(0, minX - 1) * bitmap.width / grid)
        val top = (maxOf(0, minY - 1) * bitmap.height / grid)
        val right = (minOf(grid, maxX + 2) * bitmap.width / grid).coerceAtMost(bitmap.width)
        val bottom = (minOf(grid, maxY + 2) * bitmap.height / grid).coerceAtMost(bitmap.height)
        return ChangeResult(false, Rect(left, top, maxOf(left + 1, right), maxOf(top + 1, bottom)))
    }

    private fun updateFrameCache(bitmap: Bitmap, items: List<OverlayItem>, performanceMode: String) {
        val grid = if (performanceMode == "fast") 24 else 32
        val signature = IntArray(grid * grid)
        for (y in 0 until grid) {
            val py = ((y + 0.5f) * bitmap.height / grid).toInt().coerceIn(0, bitmap.height - 1)
            for (x in 0 until grid) {
                val px = ((x + 0.5f) * bitmap.width / grid).toInt().coerceIn(0, bitmap.width - 1)
                signature[y * grid + x] = bitmap.getPixel(px, py)
            }
        }
        lastFrameSignature = signature
        lastFrameWidth = bitmap.width
        lastFrameHeight = bitmap.height
        lastOverlayItems = items
    }

    private fun clearFrameCache() {
        lastFrameSignature = null
        lastFrameWidth = 0
        lastFrameHeight = 0
        lastOverlayItems = emptyList()
    }

    // =========================================================
    // AREA SELECTOR
    // =========================================================

    private fun showAreaSelector(
        bitmap: Bitmap
    ) {

        removeAreaSelector()

        val selector =
            ScanAreaSelectorView(
                this
            )

        selector.onSelectionComplete =
            { rect ->

                serviceScope.launch {

                    settingsDataStore
                        .saveScanArea(
                            rect.left,
                            rect.top,
                            rect.right,
                            rect.bottom
                        )

                    removeAreaSelector()

                    Toast.makeText(
                        this@OverlayService,
                        "Scan area saved",
                        Toast.LENGTH_SHORT
                    ).show()

                    /*
                     * Immediately scan the newly
                     * selected area.
                     */
                    val fullBitmap =
                        captureManager
                            ?.captureFrame()

                    if (
                        fullBitmap == null
                    ) {

                        state = State.READY
                        setButtonLabel("🔍")

                        return@launch
                    }

                    val scanData =
                        prepareScanBitmap(
                            fullBitmap,
                            "last_selected_area"
                        )

                    if (
                        scanData == null
                    ) {

                        state = State.READY
                        setButtonLabel("🔍")

                        return@launch
                    }

                    state = State.WORKING
                    setButtonLabel("…")

                    processBitmap(
                        scanData.bitmap,
                        scanData.offsetX,
                        scanData.offsetY,
                        fullBitmap
                    )
                }
            }

        selector.onSelectionCancelled =
            {

                removeAreaSelector()

                state = State.READY
                setButtonLabel("🔍")

                Toast.makeText(
                    this,
                    "Area selection cancelled",
                    Toast.LENGTH_SHORT
                ).show()
            }

        val overlayType =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY

            } else {

                @Suppress("DEPRECATION")

                WindowManager.LayoutParams
                    .TYPE_PHONE
            }

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {

                gravity =
                    Gravity.TOP or
                        Gravity.START
            }

        windowManager.addView(
            selector,
            params
        )

        areaSelectorView = selector
        areaSelectorParams = params
    }

    private fun removeAreaSelector() {

        areaSelectorView?.let {

            try {

                windowManager
                    .removeView(it)

            } catch (_: Exception) {
            }
        }

        areaSelectorView = null
        areaSelectorParams = null
    }

    // =========================================================
    // OFFLINE TRANSLATION
    // =========================================================

    private suspend fun buildOfflineOverlayItems(
        blocks: List<TextBlockResult>,
        bitmap: Bitmap
    ): List<OverlayItem> {

        // OCR blocks are independent. Translate them concurrently so a page
        // with many bubbles does not wait for each bubble serially.
        return coroutineScope {
            blocks
                .filter { it.text.any(Char::isLetter) }
                .map { block ->
                    async(Dispatchers.Default) {
                        val result =
                            translationManager.detectAndTranslate(block.text)
                                ?: return@async null

                        if (!isUsableTranslation(block.text, result.translatedText)) {
                            return@async null
                        }

                        OverlayItem(
                            block.boundingBox,
                            result.translatedText,
                            TranslationOverlayView.sampleBackgroundColor(
                                bitmap,
                                block.boundingBox
                            )
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }

    // =========================================================
    // ONLINE TRANSLATION
    // =========================================================

    private suspend fun buildOnlineOverlayItems(
        blocks: List<TextBlockResult>,
        bitmap: Bitmap,
        targetLang: String
    ): List<OverlayItem> {

        val provider =
            try { settingsDataStore.onlineProvider.first() } catch (_: Exception) { "mymemory" }

        if (provider == "gemini") {
            val apiKey =
                try { settingsDataStore.geminiApiKey.first() } catch (_: Exception) { "" }

            val geminiBlocks =
                blocks.mapIndexed { index, block ->
                    GeminiTranslationManager.InputBlock(index, block.text)
                }

            val results =
                geminiTranslationManager.translatePage(
                    apiKey = apiKey,
                    blocks = geminiBlocks,
                    targetLanguage = targetLang
                )

            if (!results.isNullOrEmpty()) {
                val byId = results.associateBy { it.id }
                val overlayItems = mutableListOf<OverlayItem>()

                // Gemini can occasionally return a partial page. Keep every
                // OCR bubble visible and use the free translator only for
                // missing items instead of dropping them.
                for ((index, block) in blocks.withIndex()) {
                    val geminiTranslation = byId[index]?.translatedText
                    val translation =
                        if (isUsableTranslation(block.text, geminiTranslation)) {
                            geminiTranslation
                        } else {
                            onlineTranslationManager
                                .translate(block.text, targetLang)
                                ?.translatedText
                                ?.takeIf { isUsableTranslation(block.text, it) }
                        }

                    if (translation.isNullOrBlank()) continue

                    overlayItems.add(
                        OverlayItem(
                            block.boundingBox,
                            translation,
                            TranslationOverlayView.sampleBackgroundColor(bitmap, block.boundingBox)
                        )
                    )
                }

                if (overlayItems.isNotEmpty()) {
                    return overlayItems
                }
            }
        }

        // Reliable no-key fallback: translate OCR blocks concurrently.
        return coroutineScope {
            blocks
                .filter { it.text.any(Char::isLetter) }
                .map { block ->
                    async(Dispatchers.IO) {
                        val result = onlineTranslationManager.translate(block.text, targetLang)
                            ?: return@async null
                        if (!isUsableTranslation(block.text, result.translatedText)) {
                            return@async null
                        }
                        OverlayItem(
                            block.boundingBox,
                            result.translatedText,
                            TranslationOverlayView.sampleBackgroundColor(
                                bitmap,
                                block.boundingBox
                            )
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }

    /**
     * Reject obviously corrupted translations.
     *
     * The OCR source may legitimately contain digits or symbols. The important
     * check is the returned translation: when the source contains letters, the
     * replacement must also contain real letters. For non-text source content,
     * only an unchanged value is considered safe.
     */
    private fun isUsableTranslation(sourceText: String, translatedText: String?): Boolean {
        val source = sourceText.trim()
        val translated = translatedText?.trim().orEmpty()
        if (source.isBlank() || translated.isBlank()) return false

        val sourceLetters = source.count { it.isLetter() }
        val translatedLetters = translated.count { it.isLetter() }
        if (sourceLetters == 0) return false
        if (translatedLetters == 0) return false

        val normalizedSource = source.lowercase().replace(Regex("\\s+"), " ")
        val normalizedTranslation = translated.lowercase().replace(Regex("\\s+"), " ")
        if (normalizedSource == normalizedTranslation && source.length >= 2) return false

        // Reject obvious OCR/API corruption while allowing short legitimate
        // dialogue such as "No", "OK", or a single CJK character.
        val symbolCount = translated.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        val symbolRatio = symbolCount.toFloat() / translated.length.coerceAtLeast(1)
        if (symbolRatio > 0.55f) return false

        // A wildly expanded result is usually an API error or malformed OCR.
        val maxReasonableLength = (source.length * 8 + 40).coerceAtLeast(80)
        if (translated.length > maxReasonableLength) return false

        return true
    }

    // =========================================================
    // TRANSLATION OVERLAY
    // =========================================================

    private fun showOverlay(
        items: List<OverlayItem>
    ) {

        removeOverlayView()

        val view =
            TranslationOverlayView(
                this
            )

        view.items = items

        serviceScope.launch {

            view.overlayOpacity =
                try {

                    settingsDataStore
                        .opacity
                        .first()

                } catch (_: Exception) {

                    0.92f
                }
        }

        val overlayType =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY

            } else {

                @Suppress("DEPRECATION")

                WindowManager.LayoutParams
                    .TYPE_PHONE
            }

        val params =
            WindowManager.LayoutParams(

                WindowManager.LayoutParams.MATCH_PARENT,

                WindowManager.LayoutParams.MATCH_PARENT,

                overlayType,

                WindowManager.LayoutParams
                    .FLAG_NOT_TOUCHABLE or

                    WindowManager.LayoutParams
                        .FLAG_NOT_FOCUSABLE or

                    WindowManager.LayoutParams
                        .FLAG_LAYOUT_IN_SCREEN,

                PixelFormat.TRANSLUCENT
            )

        windowManager.addView(
            view,
            params
        )

        overlayView = view
        overlayParams = params
    }

    private fun clearOverlay() {

        removeOverlayView()

        setButtonLabel("🔍")

        state = State.READY
    }

    private fun removeOverlayView() {

        overlayView?.let {

            try {

                windowManager
                    .removeView(it)

            } catch (_: Exception) {
            }
        }

        overlayView = null
        overlayParams = null
    }

    // =========================================================
    // BUTTON LABEL
    // =========================================================

    private fun setButtonLabel(
        label: String
    ) {

        buttonView?.text = label
    }

    // =========================================================
    // NOTIFICATION
    // =========================================================

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(
                        R.string
                            .notification_channel_name
                    ),
                    NotificationManager
                        .IMPORTANCE_LOW
                )

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun buildNotification():
        Notification {

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(
                    this,
                    MainActivity::class.java
                ),
                PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat
            .Builder(
                this,
                CHANNEL_ID
            )
            .setContentTitle(
                getString(
                    R.string.app_name
                )
            )
            .setContentText(
                getString(
                    R.string.notification_text
                )
            )
            .setSmallIcon(
                android.R.drawable
                    .ic_menu_view
            )
            .setContentIntent(
                pendingIntent
            )
            .setOngoing(true)
            .build()
    }

    // =========================================================
    // SERVICE LIFECYCLE
    // =========================================================

    override fun onDestroy() {

        tapResetHandler.removeCallbacks(
            resetTapCountRunnable
        )

        removeAreaSelector()

        removeOverlayView()

        buttonView?.let {

            try {

                windowManager
                    .removeView(it)

            } catch (_: Exception) {
            }
        }

        buttonView = null

        clearFrameCache()

        captureManager?.stop()
        captureManager = null

        mediaProjection?.stop()
        mediaProjection = null

        textRecognitionManager.close()

        translationManager.close()
        onlineTranslationManager.close()
        geminiTranslationManager.close()

        serviceScope.cancel()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null


    // =========================================================
    // AREA SELECTION VIEW
    // =========================================================

    private class ScanAreaSelectorView(
        context: android.content.Context
    ) : View(context) {

        var onSelectionComplete:
                ((Rect) -> Unit)? = null

        var onSelectionCancelled:
                (() -> Unit)? = null

        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private val borderPaint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private var startX = 0f
        private var startY = 0f

        private var currentX = 0f
        private var currentY = 0f

        private var selecting = false

        init {

            setBackgroundColor(
                Color.argb(
                    80,
                    0,
                    0,
                    0
                )
            )

            paint.color =
                Color.argb(
                    70,
                    0,
                    0,
                    0
                )

            borderPaint.color =
                Color.WHITE

            borderPaint.style =
                Paint.Style.STROKE

            borderPaint.strokeWidth = 4f
        }

        override fun onDraw(
            canvas: android.graphics.Canvas
        ) {

            super.onDraw(canvas)

            if (!selecting) {

                paint.color =
                    Color.argb(
                        70,
                        0,
                        0,
                        0
                    )

                canvas.drawRect(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    paint
                )

                return
            }

            val left =
                min(
                    startX,
                    currentX
                )

            val top =
                min(
                    startY,
                    currentY
                )

            val right =
                max(
                    startX,
                    currentX
                )

            val bottom =
                max(
                    startY,
                    currentY
                )

            paint.color =
                Color.argb(
                    45,
                    255,
                    255,
                    255
                )

            canvas.drawRect(
                left,
                top,
                right,
                bottom,
                paint
            )

            canvas.drawRect(
                left,
                top,
                right,
                bottom,
                borderPaint
            )
        }

        override fun onTouchEvent(
            event: MotionEvent
        ): Boolean {

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    startX =
                        event.x

                    startY =
                        event.y

                    currentX =
                        event.x

                    currentY =
                        event.y

                    selecting = true

                    invalidate()

                    return true
                }

                MotionEvent.ACTION_MOVE -> {

                    currentX =
                        event.x

                    currentY =
                        event.y

                    invalidate()

                    return true
                }

                MotionEvent.ACTION_UP -> {

                    currentX =
                        event.x

                    currentY =
                        event.y

                    val left =
                        min(
                            startX,
                            currentX
                        ).toInt()

                    val top =
                        min(
                            startY,
                            currentY
                        ).toInt()

                    val right =
                        max(
                            startX,
                            currentX
                        ).toInt()

                    val bottom =
                        max(
                            startY,
                            currentY
                        ).toInt()

                    selecting = false

                    invalidate()

                    if (
                        right - left >= 20 &&
                        bottom - top >= 20
                    ) {

                        onSelectionComplete?.invoke(
                            Rect(
                                left,
                                top,
                                right,
                                bottom
                            )
                        )

                    } else {

                        onSelectionCancelled?.invoke()
                    }

                    return true
                }

                MotionEvent.ACTION_CANCEL -> {

                    selecting = false

                    invalidate()

                    onSelectionCancelled?.invoke()

                    return true
                }
            }

            return true
        }
    }
}
