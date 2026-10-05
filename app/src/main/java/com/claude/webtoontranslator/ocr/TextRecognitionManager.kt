package com.claude.webtoontranslator.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

/** A merged OCR region ready for translation and overlay rendering. */
data class TextBlockResult(
    val text: String,
    val boundingBox: Rect
)

/**
 * Manga/webtoon OCR pipeline.
 *
 * Instead of trusting one recognizer, this runs the language-specific ML Kit
 * recognizers, fuses overlapping results, removes duplicate detections, and
 * returns regions in deterministic manga reading order.
 */
class TextRecognitionManager {

    private val recognizers: List<Pair<String, TextRecognizer>> = listOf(
        "latin" to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        "korean" to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
        "japanese" to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
        "chinese" to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    )

    private val minConfidentLength = 1
    @Volatile private var preferredScript: String? = null

    suspend fun recognize(bitmap: Bitmap, performanceMode: String = "balanced"): List<TextBlockResult> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val allLines = mutableListOf<TextBlockResult>()

        val selected = selectRecognizers(performanceMode)
        coroutineScope {
            selected.map { (_, recognizer) ->
                async {
                    try {
                        extractLines(recognizer.process(image).await())
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().forEach { allLines += it }
        }

        if (allLines.isEmpty()) return emptyList()

        val cleaned = allLines
            .mapNotNull { normalize(it) }
            // OCR sometimes returns random punctuation/digits/box artifacts.
            // They are not translatable dialogue, so never send them to a
            // translator or draw an overlay for them.
            .filter { hasMeaningfulLetters(it.text) }
            .filter { it.text.replace("\\s".toRegex(), "").length >= minConfidentLength }

        val deduplicated = deduplicateOverlapping(cleaned)
        val merged = mergeNearbyLines(deduplicated)
        updatePreferredScript(merged)

        // Manga: right-to-left within the same vertical band, then top-to-bottom.
        return merged.sortedWith(
            compareBy<TextBlockResult> { it.boundingBox.top / 80 }
                .thenByDescending { it.boundingBox.left }
                .thenBy { it.boundingBox.top }
        )
    }

    private fun extractLines(text: Text): List<TextBlockResult> {
        val lines = mutableListOf<TextBlockResult>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (line.text.isNotBlank()) {
                    lines += TextBlockResult(line.text, Rect(box))
                }
            }
        }
        return lines
    }

    private fun normalize(block: TextBlockResult): TextBlockResult? {
        val text = block.text
            .replace("\u0000", "")
            .replace(Regex("[\\t\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        if (text.isBlank()) return null
        return TextBlockResult(text, Rect(block.boundingBox))
    }

    /**
     * Multiple ML Kit recognizers frequently return the same line.
     * Prefer the more information-rich text while preserving its geometry.
     */
    private fun deduplicateOverlapping(
        blocks: List<TextBlockResult>
    ): List<TextBlockResult> {
        val result = mutableListOf<TextBlockResult>()

        for (candidate in blocks.sortedByDescending { it.text.length }) {
            val duplicateIndex = result.indexOfFirst { existing ->
                iou(existing.boundingBox, candidate.boundingBox) >= 0.55f ||
                    (horizontalOverlapRatio(existing.boundingBox, candidate.boundingBox) >= 0.75f &&
                        verticalOverlapRatio(existing.boundingBox, candidate.boundingBox) >= 0.65f)
            }

            if (duplicateIndex < 0) {
                result += candidate
            } else {
                val existing = result[duplicateIndex]
                val chosen = chooseBetterText(existing, candidate)
                val union = Rect(existing.boundingBox)
                union.union(candidate.boundingBox)
                result[duplicateIndex] = TextBlockResult(chosen, union)
            }
        }

        return result
    }

    private fun chooseBetterText(a: TextBlockResult, b: TextBlockResult): String {
        val aa = a.text.replace("\\s".toRegex(), "")
        val bb = b.text.replace("\\s".toRegex(), "")
        return when {
            bb.length > aa.length -> b.text
            aa.length > bb.length -> a.text
            containsTargetScript(bb) && !containsTargetScript(aa) -> b.text
            else -> a.text
        }
    }

    private fun hasMeaningfulLetters(text: String): Boolean =
        text.any { it.isLetter() }

    private fun selectRecognizers(performanceMode: String): List<Pair<String, TextRecognizer>> {
        val preferred = preferredScript
        if (preferred == null || performanceMode == "quality") return recognizers
        val preferredRecognizer = recognizers.firstOrNull { it.first == preferred }
        val latin = recognizers.firstOrNull { it.first == "latin" }
        if (performanceMode == "fast") {
            return listOfNotNull(preferredRecognizer ?: latin).distinctBy { it.first }
        }
        return listOfNotNull(preferredRecognizer, latin).distinctBy { it.first }
    }

    private fun updatePreferredScript(blocks: List<TextBlockResult>) {
        if (blocks.isEmpty()) return
        val counts = mutableMapOf("japanese" to 0, "korean" to 0, "chinese" to 0, "latin" to 0)
        for (block in blocks) {
            for (c in block.text) {
                when {
                    c in '぀'..'ヿ' -> counts["japanese"] = counts.getValue("japanese") + 1
                    c in '가'..'힯' -> counts["korean"] = counts.getValue("korean") + 1
                    c in '一'..'鿿' -> counts["chinese"] = counts.getValue("chinese") + 1
                    c.isLetter() -> counts["latin"] = counts.getValue("latin") + 1
                }
            }
        }
        preferredScript = counts.maxByOrNull { it.value }?.key
    }

    private fun containsTargetScript(text: String): Boolean =
        text.any {
            it in '\u3040'..'\u30FF' ||
                it in '\uAC00'..'\uD7AF' ||
                it in '\u4E00'..'\u9FFF'
        }

    private fun mergeNearbyLines(lines: List<TextBlockResult>): List<TextBlockResult> {
        if (lines.isEmpty()) return emptyList()

        val sorted = lines.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val used = BooleanArray(sorted.size)
        val merged = mutableListOf<TextBlockResult>()

        for (i in sorted.indices) {
            if (used[i]) continue

            var currentBox = Rect(sorted[i].boundingBox)
            val currentTextParts = mutableListOf(sorted[i].text)
            used[i] = true

            var changed = true
            while (changed) {
                changed = false

                for (j in sorted.indices) {
                    if (used[j]) continue

                    val box = sorted[j].boundingBox
                    val gap = box.top - currentBox.bottom
                    val lineHeight = currentBox.height().coerceAtLeast(1)
                    val overlap = horizontalOverlapRatio(currentBox, box)

                    val horizontalGap = when {
                        box.left > currentBox.right -> box.left - currentBox.right
                        currentBox.left > box.right -> currentBox.left - box.right
                        else -> 0
                    }
                    val maxHorizontalGap = (lineHeight * 1.5f).toInt()

                    // Join only lines that plausibly belong to the same bubble:
                    // they must be vertically close, share enough horizontal span,
                    // and must not be separated by a large side-to-side gap.
                    val verticallyClose =
                        gap in -lineHeight..(lineHeight * 1.1f).toInt()
                    val horizontallyAligned =
                        overlap >= 0.45f
                    val sideGapAcceptable =
                        horizontalGap <= maxHorizontalGap

                    if (verticallyClose && horizontallyAligned && sideGapAcceptable) {
                        currentBox.union(box)
                        currentTextParts += sorted[j].text
                        used[j] = true
                        changed = true
                    }
                }
            }

            merged += TextBlockResult(
                currentTextParts
                    .joinToString(" ")
                    .replace(Regex("\\s{2,}"), " ")
                    .trim(),
                currentBox
            )
        }

        return merged
    }

    private fun iou(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0) 0f else intersection.toFloat() / union.toFloat()
    }

    private fun horizontalOverlapRatio(a: Rect, b: Rect): Float {
        val overlap = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        return overlap.toFloat() / minOf(a.width(), b.width()).coerceAtLeast(1).toFloat()
    }

    private fun verticalOverlapRatio(a: Rect, b: Rect): Float {
        val overlap = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return overlap.toFloat() / minOf(a.height(), b.height()).coerceAtLeast(1).toFloat()
    }

    fun close() {
        recognizers.forEach { it.second.close() }
    }
}
