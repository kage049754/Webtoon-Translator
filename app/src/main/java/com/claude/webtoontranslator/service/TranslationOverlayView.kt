package com.claude.webtoontranslator.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

data class OverlayItem(
    val box: Rect,
    val translatedText: String,
    val backgroundColor: Int
)

/**
 * Lightweight Android-side typesetter.
 *
 * This is intentionally safe for a screen overlay: it does not modify the
 * underlying app. It masks only the OCR region, chooses a readable color,
 * wraps both Latin and CJK text, and binary-searches a font size that fits.
 */
class TranslationOverlayView(context: Context) : View(context) {

    var items: List<OverlayItem> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var fontScale: Float = 1.0f
    var overlayOpacity: Float = 0.92f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (item in items) drawItem(canvas, item)
    }

    private fun drawItem(canvas: Canvas, item: OverlayItem) {
        if (item.translatedText.isBlank()) return

        val margin = max(3f, min(item.box.width(), item.box.height()) * 0.06f)
        val rect = RectF(
            item.box.left - margin,
            item.box.top - margin,
            item.box.right + margin,
            item.box.bottom + margin
        )

        bgPaint.color = item.backgroundColor
        bgPaint.alpha = (overlayOpacity.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawRoundRect(rect, min(rect.width(), rect.height()) * 0.18f, min(rect.width(), rect.height()) * 0.18f, bgPaint)

        val light = isColorLight(item.backgroundColor)
        val foreground = if (light) Color.BLACK else Color.WHITE
        textPaint.color = foreground
        strokePaint.color = if (light) Color.WHITE else Color.BLACK
        strokePaint.alpha = 110

        val horizontalPadding = max(8f, rect.width() * 0.06f)
        val verticalPadding = max(5f, rect.height() * 0.08f)
        val maxWidth = (rect.width() - horizontalPadding * 2).coerceAtLeast(20f)
        val maxHeight = (rect.height() - verticalPadding * 2).coerceAtLeast(16f)

        val initial = (rect.height() * 0.42f * fontScale).coerceIn(12f, 64f)
        val fitted = findFittingText(item.translatedText, maxWidth, maxHeight, initial)

        textPaint.textSize = fitted.size
        strokePaint.textSize = fitted.size
        strokePaint.strokeWidth = max(1f, fitted.size * 0.045f)

        val lineHeight = textPaint.fontSpacing
        val totalHeight = lineHeight * fitted.lines.size
        var baseline = rect.centerY() - totalHeight / 2f - textPaint.ascent()

        for (line in fitted.lines) {
            canvas.drawText(line, rect.centerX(), baseline, strokePaint)
            canvas.drawText(line, rect.centerX(), baseline, textPaint)
            baseline += lineHeight
        }
    }

    private data class FitResult(
        val lines: List<String>,
        val size: Float
    )

    private fun findFittingText(
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        initial: Float
    ): FitResult {
        var low = 10f
        var high = initial
        var best = FitResult(wrapText(text, maxWidth, high), low)

        repeat(8) {
            val mid = (low + high) / 2f
            val lines = wrapText(text, maxWidth, mid)
            val lineHeight = Paint(textPaint).apply { textSize = mid }.fontSpacing
            val fits = lines.size * lineHeight <= maxHeight

            if (fits) {
                best = FitResult(lines, mid)
                low = mid
            } else {
                high = mid
            }
        }

        return best
    }

    private fun wrapText(text: String, maxWidth: Float, size: Float): List<String> {
        textPaint.textSize = size
        val normalized = text.replace(Regex("\\s{2,}"), " ").trim()
        if (normalized.isBlank()) return emptyList()

        val tokens = if (containsCjk(normalized)) {
            normalized.flatMap { c ->
                if (c.isWhitespace()) listOf(" ") else listOf(c.toString())
            }
        } else {
            normalized.split(" ")
        }

        val lines = mutableListOf<String>()
        var current = StringBuilder()

        for (token in tokens) {
            val separator = if (containsCjk(normalized)) "" else if (current.isEmpty()) "" else " "
            val candidate = current.toString() + separator + token

            if (current.isNotEmpty() && textPaint.measureText(candidate) > maxWidth) {
                lines += current.toString().trim()
                current = StringBuilder(token.trim())
            } else if (current.isEmpty() && textPaint.measureText(token) > maxWidth) {
                // Force-break an unusually long word.
                var piece = StringBuilder()
                for (c in token) {
                    val next = piece.toString() + c
                    if (piece.isNotEmpty() && textPaint.measureText(next) > maxWidth) {
                        lines += piece.toString()
                        piece = StringBuilder(c.toString())
                    } else {
                        piece.append(c)
                    }
                }
                current = piece
            } else {
                current.append(separator).append(token)
            }
        }

        if (current.isNotEmpty()) lines += current.toString().trim()
        return lines.filter { it.isNotBlank() }
    }

    private fun containsCjk(text: String): Boolean =
        text.any {
            it in '\u3040'..'\u30FF' ||
                it in '\uAC00'..'\uD7AF' ||
                it in '\u4E00'..'\u9FFF'
        }

    private fun isColorLight(color: Int): Boolean {
        val luminance =
            0.299 * Color.red(color) +
                0.587 * Color.green(color) +
                0.114 * Color.blue(color)
        return luminance > 150
    }

    companion object {
        /**
         * Samples a wider ring around the OCR region and uses a robust average.
         * This avoids a single dark pixel making a white speech bubble black.
         */
        fun sampleBackgroundColor(bitmap: Bitmap, box: Rect): Int {
            return try {
                val left = box.left.coerceIn(0, bitmap.width - 1)
                val top = box.top.coerceIn(0, bitmap.height - 1)
                val right = box.right.coerceIn(0, bitmap.width - 1)
                val bottom = box.bottom.coerceIn(0, bitmap.height - 1)

                val points = listOf(
                    left, (left + right) / 2, right
                ).flatMap { x ->
                    listOf(
                        (top - 3).coerceAtLeast(0),
                        (bottom + 3).coerceAtMost(bitmap.height - 1)
                    ).map { y -> bitmap.getPixel(x, y) }
                } + listOf(
                    (left - 3).coerceAtLeast(0),
                    (right + 3).coerceAtMost(bitmap.width - 1)
                ).flatMap { x ->
                    listOf(top, (top + bottom) / 2, bottom).map { y ->
                        bitmap.getPixel(x, y)
                    }
                }

                val rs = points.map { Color.red(it) }.sorted()
                val gs = points.map { Color.green(it) }.sorted()
                val bs = points.map { Color.blue(it) }.sorted()
                val middle = points.size / 2

                Color.rgb(rs[middle], gs[middle], bs[middle])
            } catch (_: Exception) {
                Color.WHITE
            }
        }
    }
}
