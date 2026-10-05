package com.claude.webtoontranslator

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.claude.webtoontranslator.ocr.OnlineTranslationManager
import com.claude.webtoontranslator.ocr.TranslationManager
import com.claude.webtoontranslator.service.OverlayService
import com.claude.webtoontranslator.util.SettingsDataStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingStartAfterOverlayPermission = false

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { _ ->
            requestMediaProjection()
        }

    private val mediaProjectionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (
                result.resultCode == RESULT_OK &&
                result.data != null
            ) {

                val intent =
                    Intent(
                        this,
                        OverlayService::class.java
                    ).apply {

                        putExtra(
                            OverlayService.EXTRA_RESULT_CODE,
                            result.resultCode
                        )

                        putExtra(
                            OverlayService.EXTRA_RESULT_DATA,
                            result.data
                        )
                    }

                try {

                    ContextCompat.startForegroundService(
                        this,
                        intent
                    )

                    Toast.makeText(
                        this,
                        "Overlay starting…",
                        Toast.LENGTH_SHORT
                    ).show()

                } catch (e: Exception) {

                    Toast.makeText(
                        this,
                        "Couldn't start the overlay service: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } else {

                Toast.makeText(
                    this,
                    "Screen capture permission wasn't granted, so the overlay can't start.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContent {

            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary =
                            Color(0xFF6750A4),

                        secondary =
                            Color(0xFF03DAC5),

                        background =
                            Color(0xFF1C1B1F),

                        surface =
                            Color(0xFF1C1B1F)
                    )
            ) {

                MainScreen(
                    onStartOverlay = {
                        startOverlayFlow()
                    },

                    settingsDataStore =
                        SettingsDataStore(this)
                )
            }
        }
    }

    override fun onResume() {

        super.onResume()

        if (
            pendingStartAfterOverlayPermission
        ) {

            checkOverlayPermissionWithRetry(
                attemptsLeft = 5
            )
        }
    }

    private fun checkOverlayPermissionWithRetry(
        attemptsLeft: Int
    ) {

        if (
            Settings.canDrawOverlays(this)
        ) {

            pendingStartAfterOverlayPermission =
                false

            requestNotificationPermissionThenProject()

            return
        }

        if (
            attemptsLeft <= 0
        ) {

            pendingStartAfterOverlayPermission =
                false

            Toast.makeText(
                this,
                "\"Display over other apps\" isn't granted yet. Please enable it and tap Start Overlay again.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        android.os.Handler(
            android.os.Looper.getMainLooper()
        ).postDelayed({

            checkOverlayPermissionWithRetry(
                attemptsLeft - 1
            )

        }, 400)
    }

    private fun startOverlayFlow() {

        Toast.makeText(
            this,
            "Checking permissions…",
            Toast.LENGTH_SHORT
        ).show()

        if (
            !Settings.canDrawOverlays(this)
        ) {

            pendingStartAfterOverlayPermission =
                true

            try {

                val intent =
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse(
                            "package:$packageName"
                        )
                    )

                startActivity(intent)

            } catch (e: Exception) {

                pendingStartAfterOverlayPermission =
                    false

                Toast.makeText(
                    this,
                    "Couldn't open overlay permission settings on this device: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }

            return
        }

        requestNotificationPermissionThenProject()
    }

    private fun requestNotificationPermissionThenProject() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            val granted =
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) ==
                    PackageManager.PERMISSION_GRANTED

            if (!granted) {

                notificationPermissionLauncher.launch(
                    android.Manifest.permission.POST_NOTIFICATIONS
                )

                return
            }
        }

        requestMediaProjection()
    }

    private fun requestMediaProjection() {

        try {

            val manager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjectionLauncher.launch(
                manager.createScreenCaptureIntent()
            )

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "Couldn't start screen capture permission dialog: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

@Composable
fun MainScreen(
    onStartOverlay: () -> Unit,
    settingsDataStore: SettingsDataStore
) {

    val scope =
        rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Upgrade API keys saved by older versions from plaintext to Keystore-backed ciphertext.
        settingsDataStore.migrateGeminiApiKeyIfNeeded()
    }

    val context =
    androidx.compose.ui.platform.LocalContext.current    

    val mode by
        settingsDataStore
            .translationMode
            .collectAsState(
                initial = "offline"
            )

    val targetLang by
        settingsDataStore
            .onlineTargetLanguage
            .collectAsState(
                initial = "en"
            )

    val onlineProvider by
        settingsDataStore
            .onlineProvider
            .collectAsState(initial = "mymemory")

    val geminiApiKey by
        settingsDataStore
            .geminiApiKey
            .collectAsState(initial = "")

    val performanceMode by
        settingsDataStore
            .performanceMode
            .collectAsState(initial = "balanced")

    val modelsDownloaded by
        settingsDataStore
            .modelsDownloaded
            .collectAsState(initial = false)
    var downloadingModels by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var geminiKeyInput by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
    var showGeminiKey by remember { mutableStateOf(false) }
    var showTargetLanguageMenu by remember { mutableStateOf(false) }

    val scanMode by
        settingsDataStore
            .scanMode
            .collectAsState(
                initial = "whole_screen"
            )

    val hasSavedArea by
        settingsDataStore
            .hasSavedArea
            .collectAsState(
                initial = false
            )

    var langMenuExpanded by
        remember {
            mutableStateOf(false)
        }

    Surface(
        modifier =
            Modifier.fillMaxSize()
    ) {

        Column(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(
                        rememberScrollState()
                    ),

            horizontalAlignment =
                Alignment.CenterHorizontally,

            verticalArrangement =
                Arrangement.Top
        ) {

            // =================================================
            // APP ICON
            // =================================================

            Box(

                modifier =
                    Modifier
                        .size(72.dp)
                        .background(
                            Color(0xFF6750A4),
                            RoundedCornerShape(20.dp)
                        ),

                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    "訳",
                    fontSize = 32.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Webtoon Translator",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showSettings = true }) {
                    Text("⚙", fontSize = 24.sp)
                }
            }

            Spacer(Modifier.height(6.dp))

            Surface(
                shape = RoundedCornerShape(50.dp),
                color = if (mode == "online") Color(0xFF263A33) else Color(0xFF302D38)
            ) {
                Text(
                    if (mode == "online")
                        "ONLINE • " + if (onlineProvider == "gemini") "Gemini AI" else "Free fallback"
                    else
                        "OFFLINE • On-device translation",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            Text(

                if (
                    mode == "online"
                ) {

                    "Auto-detects any language on screen and overlays a translation into your chosen language."

                } else {

                    "Reads Korean, Japanese, Chinese, Spanish, or French text on screen and overlays an English translation right on top of the original."
                },

                fontSize = 15.sp,

                color =
                    Color(0xFFB0AAB8),

                modifier =
                    Modifier.padding(
                        horizontal = 8.dp
                    )
            )

            Spacer(
                modifier =
                    Modifier.height(28.dp)
            )

            // =================================================
            // START BUTTON
            // =================================================

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartOverlay,
                    modifier = Modifier
                        .weight(1.35f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6750A4)
                    )
                ) {
                    Text(
                        "Start Overlay",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            if (!hasSavedArea) {
                                settingsDataStore.setScanMode("select_area")
                                Toast.makeText(
                                    context,
                                    "Start Overlay, then drag to select and save your scan area.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else if (scanMode == "last_selected_area") {
                                settingsDataStore.setScanMode("whole_screen")
                                Toast.makeText(
                                    context,
                                    "Full screen mode selected.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                settingsDataStore.setScanMode("last_selected_area")
                                Toast.makeText(
                                    context,
                                    "Last selected scan area selected.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(0.85f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        when {
                            !hasSavedArea -> "Scan Area"
                            scanMode == "last_selected_area" -> "Full Screen"
                            else -> "Saved Area"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            Text(

                "The floating button and translations only appear over other apps after you grant the required permissions.",

                fontSize = 12.sp,

                color =
                    Color(0xFF8A8391),

                modifier =
                    Modifier.padding(
                        horizontal = 8.dp
                    )
            )

            Spacer(
                modifier =
                    Modifier.height(28.dp)
            )

            // =================================================
            // SIMPLE MODE / PROVIDER
            // =================================================

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2830), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (mode == "online") "Online mode" else "Offline mode", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (mode == "online") "Uses Gemini or the free fallback." else "Uses downloaded on-device translation models.",
                            fontSize = 12.sp, color = Color(0xFF8A8391)
                        )
                    }
                    Switch(
                        checked = mode == "online",
                        onCheckedChange = { isOnline ->
                            scope.launch { settingsDataStore.setTranslationMode(if (isOnline) "online" else "offline") }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF03DAC5))
                    )
                }
                if (mode == "online") {
                    Spacer(Modifier.height(12.dp))
                    Text("Online engine", fontSize = 13.sp, color = Color(0xFFB0AAB8))
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = onlineProvider == "mymemory",
                            onClick = { scope.launch { settingsDataStore.setOnlineProvider("mymemory") } },
                            label = { Text("Free fallback") }
                        )
                        FilterChip(
                            selected = onlineProvider == "gemini",
                            onClick = { scope.launch { settingsDataStore.setOnlineProvider("gemini") } },
                            label = { Text("Gemini") }
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            if (showSettings) {
                AlertDialog(
                    onDismissRequest = { showSettings = false },
                    title = { Text("Settings") },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Gemini", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text(
                                "Your Gemini API key is encrypted with Android Keystore and is never committed to GitHub.",
                                fontSize = 12.sp,
                                color = Color(0xFF8A8391)
                            )
                            OutlinedTextField(
                                value = geminiKeyInput,
                                onValueChange = { geminiKeyInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Gemini API key") },
                                placeholder = { Text("Paste your Gemini API key") },
                                visualTransformation =
                                    if (showGeminiKey) VisualTransformation.None
                                    else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { showGeminiKey = !showGeminiKey }) {
                                        Text(if (showGeminiKey) "Hide" else "Show")
                                    }
                                }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            settingsDataStore.setGeminiApiKey(geminiKeyInput.trim())
                                            Toast.makeText(context, "Gemini key saved.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Save key") }
                                OutlinedButton(
                                    onClick = {
                                        geminiKeyInput = ""
                                        scope.launch {
                                            settingsDataStore.setGeminiApiKey("")
                                            Toast.makeText(context, "Gemini key cleared.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Clear") }
                            }
                            Divider()
                            Text("Online target language", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Box {
                                val currentLabel = OnlineTranslationManager.SUPPORTED_TARGET_LANGUAGES.firstOrNull { it.first == targetLang }?.second ?: "English"
                                OutlinedButton(onClick = { showTargetLanguageMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(currentLabel) }
                                DropdownMenu(expanded = showTargetLanguageMenu, onDismissRequest = { showTargetLanguageMenu = false }) {
                                    OnlineTranslationManager.SUPPORTED_TARGET_LANGUAGES.forEach { (code, label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            showTargetLanguageMenu = false
                                            scope.launch { settingsDataStore.setOnlineTargetLanguage(code) }
                                        })
                                    }
                                }
                            }
                            Text("Performance", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            listOf("fast" to "Fast", "balanced" to "Balanced", "quality" to "Quality").forEach { (value, label) ->
                                ScanModeOption(
                                    title = label,
                                    description = when (value) {
                                        "fast" -> "Lowest processing time and battery use."
                                        "quality" -> "Maximum OCR coverage."
                                        else -> "Recommended balance."
                                    },
                                    selected = performanceMode == value,
                                    onClick = { scope.launch { settingsDataStore.setPerformanceMode(value) } }
                                )
                            }
                            Text("Offline models", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(if (modelsDownloaded) "Downloaded / ready" else "Not downloaded yet", fontSize = 12.sp, color = Color(0xFF8A8391))
                            Button(
                                onClick = {
                                    if (!downloadingModels) {
                                        downloadingModels = true
                                        scope.launch {
                                            val manager = TranslationManager()
                                            try {
                                                manager.preDownloadModels()
                                                settingsDataStore.setModelsDownloaded(true)
                                                Toast.makeText(context, "Offline models are ready.", Toast.LENGTH_SHORT).show()
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "Model download failed. Check your internet connection.", Toast.LENGTH_LONG).show()
                                            } finally {
                                                manager.close()
                                                downloadingModels = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !downloadingModels
                            ) { Text(if (downloadingModels) "Downloading…" else "Download / Refresh Models") }
                            Text("Scan area", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(if (hasSavedArea) "A saved scan area is available." else "No saved area yet.", fontSize = 12.sp, color = Color(0xFF8A8391))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch { settingsDataStore.setScanMode("select_area") }
                                        showSettings = false
                                        Toast.makeText(context, "Start Overlay, then use the floating button to select the area.", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Select Area") }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            settingsDataStore.clearSavedScanArea()
                                            settingsDataStore.setScanMode("whole_screen")
                                        }
                                        Toast.makeText(context, "Saved area cleared.", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = hasSavedArea
                                ) { Text("Clear Area") }
                            }
                            Divider()
                            Text("Current mode: " + if (mode == "online") "Online" else "Offline", fontSize = 13.sp)
                            Text("Online provider: " + if (onlineProvider == "gemini") "Gemini AI" else "Free fallback", fontSize = 13.sp)
                            Text("Performance: " + performanceMode.replaceFirstChar { it.uppercase() }, fontSize = 13.sp)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showSettings = false }) { Text("Done") }
                    }
                )
            }

            if (false) {
            // =================================================
            // OFFLINE MODEL MANAGER
            // =================================================

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2830), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    "Offline models",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    if (modelsDownloaded) {
                        "Models are marked ready. They stay cached on the device."
                    } else {
                        "Download the language models once while online. Translation can then work without internet."
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF8A8391)
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (!downloadingModels) {
                            downloadingModels = true
                            scope.launch {
                                val manager = TranslationManager()
                                try {
                                    manager.preDownloadModels()
                                    settingsDataStore.setModelsDownloaded(true)
                                    Toast.makeText(context, "Offline models are ready.", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Model download failed. Check your internet connection.", Toast.LENGTH_LONG).show()
                                } finally {
                                    manager.close()
                                    downloadingModels = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !downloadingModels
                ) {
                    Text(if (downloadingModels) "Downloading…" else "Download / Refresh Models")
                }
            }

            Spacer(Modifier.height(28.dp))

            // =================================================
            // PERFORMANCE MODE
            // =================================================

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2830), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    "Translation performance",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    when (performanceMode) {
                        "fast" -> "Fast: prioritize speed, reuse unchanged regions, and use the learned OCR script."
                        "quality" -> "Quality: run all OCR recognizers and validate results more strictly."
                        else -> "Balanced: smart OCR plus safe fallbacks. Recommended."
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF8A8391)
                )
                Spacer(Modifier.height(12.dp))
                listOf(
                    "fast" to "Fast",
                    "balanced" to "Balanced",
                    "quality" to "Quality"
                ).forEach { (value, label) ->
                    ScanModeOption(
                        title = label,
                        description = when (value) {
                            "fast" -> "Best for scrolling and lower battery use."
                            "quality" -> "Best OCR coverage for difficult pages."
                            else -> "Best overall speed and accuracy."
                        },
                        selected = performanceMode == value,
                        onClick = {
                            scope.launch { settingsDataStore.setPerformanceMode(value) }
                        }
                    )
                    if (value != "quality") Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(28.dp))

            // =================================================
            // SCAN AREA SETTINGS
            // =================================================

            Column(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0xFF2A2830),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp)
            ) {

                Text(

                    "Scan Area",

                    fontSize = 18.sp,

                    fontWeight =
                        FontWeight.SemiBold,

                    color =
                        MaterialTheme
                            .colorScheme
                            .onBackground
                )

                Spacer(
                    modifier =
                        Modifier.height(6.dp)
                )

                Text(

                    "Choose which part of the screen OCR should scan.",

                    fontSize = 12.sp,

                    color =
                        Color(0xFF8A8391)
                )

                Spacer(
                    modifier =
                        Modifier.height(14.dp)
                )

                // Whole Screen
                ScanModeOption(

                    title = "Whole Screen",

                    description =
                        "Scan the entire screen.",

                    selected =
                        scanMode ==
                            "whole_screen",

                    onClick = {

                        scope.launch {

                            settingsDataStore
                                .setScanMode(
                                    "whole_screen"
                                )
                        }
                    }
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                // Select Area
                ScanModeOption(

                    title = "Select Area",

                    description =
                        "Choose a new area when you scan.",

                    selected =
                        scanMode ==
                            "select_area",

                    onClick = {

                        scope.launch {

                            settingsDataStore
                                .setScanMode(
                                    "select_area"
                                )
                        }

                        Toast.makeText(
    context,
    "Tap the floating 🔍 button to select an area.",
    Toast.LENGTH_SHORT
).show()
                    }
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                // Last selected area
                ScanModeOption(

                    title =
                        "Use Last Selected Area",

                    description =

                        if (hasSavedArea)
                            "Reuse the saved scan area."
                        else
                            "No area saved yet.",

                    selected =
                        scanMode ==
                            "last_selected_area",

                    enabled =
                        hasSavedArea,

                    onClick = {

                        if (hasSavedArea) {

                            scope.launch {

                                settingsDataStore
                                    .setScanMode(
                                        "last_selected_area"
                                    )
                            }
                        }
                    }
                )

                if (
                    hasSavedArea
                ) {

                    Spacer(
                        modifier =
                            Modifier.height(12.dp)
                    )

                    OutlinedButton(

                        onClick = {

                            scope.launch {

                                settingsDataStore
                                    .clearSavedScanArea()

                                settingsDataStore
                                    .setScanMode(
                                        "whole_screen"
                                    )
                            }

                            Toast.makeText(
    context,
    "Saved scan area cleared.",
    Toast.LENGTH_SHORT
).show()
                        },

                        modifier =
                            Modifier.fillMaxWidth()
                    ) {

                        Text(
                            "Clear Saved Area"
                        )
                    }
                }
            }

            Spacer(
                modifier =
                    Modifier.height(28.dp)
            )

            // =================================================
            // INSTRUCTIONS
            // =================================================

            InstructionRow(
                step = "1",
                text =
                    "Tap \"Start Overlay\" and grant the permissions."
            )

            InstructionRow(
                step = "2",
                text =
                    "Open your webtoon/manga app or browser."
            )

            InstructionRow(
                step = "3",
                text =
                    "Tap ▶ to start, then 🔍 to scan."
            )

            InstructionRow(
                step = "4",
                text =
                    "Tap ⏹ to stop/clear the current translation."
            )

            InstructionRow(
                step = "5",
                text =
                    "Hold the floating button for 5 seconds to completely close the overlay."
            )

            }
            Spacer(
                modifier =
                    Modifier.height(40.dp)
            )

        }
    }
}

@Composable
fun ScanModeOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {

    Surface(
    modifier =
        Modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                onClick = onClick
            ),

    shape =
        RoundedCornerShape(12.dp),

    color =
        if (selected)
            Color(0xFF3B3548)
        else
            Color(0xFF242229)
) {

        Row(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            RadioButton(

                selected =
                    selected,

                onClick =
                    if (enabled)
                        onClick
                    else
                        null,

                enabled =
                    enabled
            )

            Spacer(
                modifier =
                    Modifier.width(10.dp)
            )

            Column {

                Text(

                    title,

                    fontSize = 15.sp,

                    fontWeight =
                        FontWeight.SemiBold,

                    color =
                        if (enabled)
                            Color.White
                        else
                            Color(0xFF66616B)
                )

                Text(

                    description,

                    fontSize = 11.sp,

                    color =
                        if (enabled)
                            Color(0xFF9D97A3)
                        else
                            Color(0xFF5E5963)
                )
            }
        }
    }
}

@Composable
fun InstructionRow(
    step: String,
    text: String
) {

    Row(

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Box(

            modifier =
                Modifier
                    .size(26.dp)
                    .background(
                        Color(0xFF03DAC5),
                        RoundedCornerShape(8.dp)
                    ),

            contentAlignment =
                Alignment.Center
        ) {

            Text(
                step,
                fontSize = 13.sp,
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(
            modifier =
                Modifier.width(12.dp)
        )

        Text(
            text,
            fontSize = 14.sp,
            color = Color(0xFFCFC9D3)
        )
    }
}
