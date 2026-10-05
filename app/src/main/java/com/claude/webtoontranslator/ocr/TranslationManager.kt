package com.claude.webtoontranslator.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline manga translation.
 *
 * Adds script fallback for short OCR snippets and a process-local translation
 * memory so repeated names/phrases are translated consistently during a chapter.
 */
class TranslationManager {

    private val languageIdentifier = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()
    private val memory = ConcurrentHashMap<String, TranslationResult>()

    private val supportedSources = setOf(
        TranslateLanguage.KOREAN,
        TranslateLanguage.JAPANESE,
        TranslateLanguage.CHINESE,
        TranslateLanguage.SPANISH,
        TranslateLanguage.FRENCH,
        TranslateLanguage.ENGLISH
    )

    suspend fun preDownloadModels() {
        val conditions = DownloadConditions.Builder().build()
        for (language in supportedSources) {
            if (language == TranslateLanguage.ENGLISH) continue
            try {
                getOrCreateTranslator(language)
                    .downloadModelIfNeeded(conditions)
                    .await()
            } catch (_: Exception) {
                // Model download is retried when the language is actually needed.
            }
        }
    }

    private fun getOrCreateTranslator(sourceLanguage: String): Translator =
        translators.getOrPut(sourceLanguage) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            Translation.getClient(options)
        }

    suspend fun detectAndTranslate(text: String): TranslationResult? {
        val cleanText = normalizeText(text)
        if (cleanText.isBlank()) return null

        val detected = try {
            languageIdentifier.identifyLanguage(cleanText).await()
        } catch (_: Exception) {
            "und"
        }

        val language = mapToMlKitLanguage(detected)
            ?: detectScriptLanguage(cleanText)
            ?: return null

        if (language == TranslateLanguage.ENGLISH || language !in supportedSources) {
            return null
        }

        val key = "${language}|${cleanText.lowercase(Locale.ROOT)}"
        memory[key]?.let { return it.copy(fromMemory = true) }

        return try {
            val translator = getOrCreateTranslator(language)

            try {
                translator.downloadModelIfNeeded(
                    DownloadConditions.Builder().requireWifi().build()
                ).await()
            } catch (_: Exception) {
                translator.downloadModelIfNeeded(
                    DownloadConditions.Builder().build()
                ).await()
            }

            val translated = translator.translate(cleanText).await()
                .replace(Regex("\\s{2,}"), " ")
                .trim()

            if (translated.isBlank()) return null

            val result = TranslationResult(
                sourceLanguage = language,
                translatedText = translated,
                fromMemory = false
            )
            memory[key] = result
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeText(text: String): String =
        text.replace("\u0000", "")
            .replace(Regex("[\\t\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    private fun mapToMlKitLanguage(code: String): String? = when (code) {
        "ko" -> TranslateLanguage.KOREAN
        "ja" -> TranslateLanguage.JAPANESE
        "zh" -> TranslateLanguage.CHINESE
        "es" -> TranslateLanguage.SPANISH
        "fr" -> TranslateLanguage.FRENCH
        "en" -> TranslateLanguage.ENGLISH
        else -> null
    }

    private fun detectScriptLanguage(text: String): String? {
        var japanese = 0
        var korean = 0
        var chinese = 0
        var latin = 0

        for (c in text) {
            when {
                c in '\u3040'..'\u30FF' -> japanese++
                c in '\uAC00'..'\uD7AF' -> korean++
                c in '\u4E00'..'\u9FFF' -> chinese++
                c.isLetter() && c.code < 0x0250 -> latin++
            }
        }

        return when {
            japanese > 0 -> TranslateLanguage.JAPANESE
            korean > 0 -> TranslateLanguage.KOREAN
            chinese > 0 -> TranslateLanguage.CHINESE
            latin > 0 -> TranslateLanguage.ENGLISH
            else -> null
        }
    }

    fun clearMemory() {
        memory.clear()
    }

    fun close() {
        languageIdentifier.close()
        translators.values.forEach { it.close() }
        translators.clear()
        memory.clear()
    }

    data class TranslationResult(
        val sourceLanguage: String,
        val translatedText: String,
        val fromMemory: Boolean = false
    )
}
