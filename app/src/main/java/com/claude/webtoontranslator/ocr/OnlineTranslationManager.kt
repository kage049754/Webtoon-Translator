package com.claude.webtoontranslator.ocr

import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

class OnlineTranslationManager {

    companion object {
        private const val ENDPOINT =
            "https://api.mymemory.translated.net/get"

        private const val TIMEOUT_MS = 12_000

        // MyMemory documents a 500-byte limit for the q parameter.
        // Keep a safety margin.
        private const val MAX_QUERY_BYTES = 450
        private const val MAX_CONCURRENT_REQUESTS = 3

        val SUPPORTED_TARGET_LANGUAGES: List<Pair<String, String>> =
            listOf(
                "en" to "English",
                "fr" to "French",
                "es" to "Spanish",
                "de" to "German",
                "pt" to "Portuguese",
                "it" to "Italian",
                "ru" to "Russian",
                "ja" to "Japanese",
                "ko" to "Korean",
                "zh" to "Chinese",
                "vi" to "Vietnamese",
                "th" to "Thai",
                "id" to "Indonesian",
                "ar" to "Arabic",
                "hi" to "Hindi",
                "tr" to "Turkish",
                "nl" to "Dutch",
                "pl" to "Polish"
            )
    }

    private val languageIdentifier =
        LanguageIdentification.getClient()

    // Re-scanning the same webtoon frame should not hit the public endpoint again.
    private val translationCache = ConcurrentHashMap<String, OnlineTranslationResult>()
    private val requestSlots = Semaphore(MAX_CONCURRENT_REQUESTS, true)

    data class OnlineTranslationResult(
        val detectedSourceLanguage: String?,
        val translatedText: String
    )

    /**
     * Detect the source language and translate it to the selected target.
     */
    suspend fun translate(
        text: String,
        targetLanguageCode: String
    ): OnlineTranslationResult? =
        withContext(Dispatchers.IO) {

            val cleanText = text
                .replace("\u0000", "")
                .trim()

            if (cleanText.isBlank()) {
                return@withContext null
            }

            /*
             * First try ML Kit language identification.
             */
            val detectedLanguage = try {
                languageIdentifier
                    .identifyLanguage(cleanText)
                    .await()
            } catch (_: Exception) {
                null
            }

            /*
             * Normalize ML Kit result.
             *
             * Examples:
             * ja       -> ja
             * ja-JP    -> ja
             * zh-CN    -> zh
             * ko-KR    -> ko
             */
            val sourceLanguage =
                normalizeLanguage(detectedLanguage)
                    ?: detectScriptLanguage(cleanText)
                    ?: return@withContext null

            val targetLanguage =
                normalizeLanguage(targetLanguageCode)
                    ?: return@withContext null

            /*
             * No overlay is needed when the requested language already matches
             * the detected source language.
             */
            if (sourceLanguage == targetLanguage) {
                return@withContext null
            }

            val cacheKey = "${sourceLanguage}|${targetLanguage}|${cleanText.lowercase(Locale.ROOT)}"
            translationCache[cacheKey]?.let { return@withContext it }

            /*
             * MyMemory has a byte limit, so split large OCR results.
             */
            val chunks =
                splitByUtf8Bytes(
                    text = cleanText,
                    maxBytes = MAX_QUERY_BYTES
                )

            if (chunks.isEmpty()) {
                return@withContext null
            }

            // Independent chunks can be translated concurrently. Keep the
            // concurrency small enough to avoid hammering the public endpoint.
            val translatedParts = coroutineScope {
                chunks.map { chunk ->
                    async(Dispatchers.IO) {
                        requestSlots.acquire()
                        try {
                            translateChunk(
                                text = chunk,
                                sourceLanguage = sourceLanguage,
                                targetLanguage = targetLanguage
                            )
                        } finally {
                            requestSlots.release()
                        }
                    }
                }.awaitAll()
            }

            if (translatedParts.any { it.isNullOrBlank() }) {
                return@withContext null
            }

            val result = OnlineTranslationResult(
                detectedSourceLanguage = sourceLanguage,
                translatedText = translatedParts
                    .joinToString(" ")
                    .trim()
            )
            if (translationCache.size >= 512) {
                val oldestKey = translationCache.keys.firstOrNull()
                if (oldestKey != null) translationCache.remove(oldestKey)
            }
            translationCache[cacheKey] = result
            result
        }

    /**
     * Convert language identifiers to simple two-letter codes.
     */
    private fun normalizeLanguage(
        code: String?
    ): String? {

        if (code.isNullOrBlank()) {
            return null
        }

        if (code.equals("und", ignoreCase = true)) {
            return null
        }

        val normalized =
            code
                .lowercase()
                .substringBefore("-")
                .substringBefore("_")
                .trim()

        return normalized.takeIf {
            it.length == 2
        }
    }

    /**
     * Fallback language detection for short manga text.
     *
     * ML Kit can sometimes return "und" when the OCR text
     * is very short, such as:
     *
     * "こんにちは"
     * "ありがとう"
     * "안녕"
     * "你好"
     */
    private fun detectScriptLanguage(
        text: String
    ): String? {

        var japaneseCount = 0
        var koreanCount = 0
        var chineseCount = 0

        for (character in text) {

            when {

                // Hiragana
                character in '\u3040'..'\u309F' -> {
                    japaneseCount++
                }

                // Katakana
                character in '\u30A0'..'\u30FF' -> {
                    japaneseCount++
                }

                // Hangul
                character in '\uAC00'..'\uD7AF' -> {
                    koreanCount++
                }

                // CJK Unified Ideographs
                character in '\u4E00'..'\u9FFF' -> {
                    chineseCount++
                }
            }
        }

        return when {

            japaneseCount > 0 ->
                "ja"

            koreanCount > 0 ->
                "ko"

            chineseCount > 0 ->
                "zh"

            else ->
                null
        }
    }

    /**
     * Split text without exceeding the UTF-8 byte limit.
     *
     * This is important for Japanese, Korean and Chinese
     * because one character can use multiple UTF-8 bytes.
     */
    private fun splitByUtf8Bytes(
        text: String,
        maxBytes: Int
    ): List<String> {

        val result = mutableListOf<String>()

        var current = StringBuilder()

        fun flushCurrent() {

            val value =
                current
                    .toString()
                    .trim()

            if (value.isNotBlank()) {
                result += value
            }

            current = StringBuilder()
        }

        /*
         * First try splitting around whitespace.
         */
        val pieces =
            text.split(
                Regex("(?<=\\s)|(?=\\s)")
            )

        for (piece in pieces) {

            val candidate =
                current.toString() + piece

            val candidateBytes =
                candidate
                    .toByteArray(StandardCharsets.UTF_8)
                    .size

            if (candidateBytes <= maxBytes) {

                current.append(piece)

            } else {

                flushCurrent()

                val pieceBytes =
                    piece
                        .toByteArray(StandardCharsets.UTF_8)
                        .size

                if (pieceBytes <= maxBytes) {

                    current.append(piece)

                } else {

                    /*
                     * CJK text often has no spaces.
                     * Split character-by-character when needed.
                     */
                    var smallPart = StringBuilder()

                    for (character in piece) {

                        val next =
                            smallPart
                                .toString() + character

                        val nextBytes =
                            next
                                .toByteArray(StandardCharsets.UTF_8)
                                .size

                        if (nextBytes > maxBytes) {

                            if (smallPart.isNotEmpty()) {
                                result +=
                                    smallPart
                                        .toString()
                                        .trim()
                            }

                            smallPart =
                                StringBuilder()
                        }

                        smallPart.append(character)
                    }

                    if (smallPart.isNotEmpty()) {
                        current.append(smallPart)
                    }
                }
            }
        }

        flushCurrent()

        return result.filter {
            it.isNotBlank()
        }
    }

    /**
     * Send one translation request to MyMemory.
     */
    private fun translateChunk(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String? {

        val encodedText =
            URLEncoder.encode(
                text,
                StandardCharsets.UTF_8.name()
            )

        val languagePair =
            "$sourceLanguage|$targetLanguage"

        val encodedLanguagePair =
            URLEncoder.encode(
                languagePair,
                StandardCharsets.UTF_8.name()
            )

        val requestUrl =
            "$ENDPOINT" +
                "?q=$encodedText" +
                "&langpair=$encodedLanguagePair" +
                "&mt=1"

        repeat(3) { attempt ->
            var connection: HttpURLConnection? = null

            try {
                connection =
                    (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                        useCaches = false
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", "WebtoonTranslator/1.0")
                    }

                val responseCode = connection.responseCode

                if (responseCode in 200..299) {
                    val response =
                        connection.inputStream
                            .bufferedReader(StandardCharsets.UTF_8)
                            .use { it.readText() }

                    if (response.isBlank()) return null

                    val json = JSONObject(response)
                    if (json.optInt("responseStatus", 0) != 200) return null

                    val translated =
                        json.optJSONObject("responseData")
                            ?.optString("translatedText", "")
                            ?.trim()

                    return if (!translated.isNullOrBlank()) translated else null
                }

                // Retry only transient failures such as rate limiting or server errors.
                val retryable =
                    responseCode == 408 ||
                        responseCode == 429 ||
                        responseCode >= 500

                if (!retryable) return null
            } catch (_: Exception) {
                // Network interruptions are transient; retry a small bounded number of times.
            } finally {
                connection?.disconnect()
            }

            if (attempt < 2) {
                Thread.sleep(350L * (attempt + 1))
            }
        }

        return null
    }

    /**
     * Release ML Kit resources.
     */
    fun close() {

        languageIdentifier.close()
        translationCache.clear()
    }
}
