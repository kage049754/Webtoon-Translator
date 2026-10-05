package com.claude.webtoontranslator.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiTranslationManager {
    companion object {
        private const val MODEL = "gemini-3.6-flash"
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
        private const val TIMEOUT_MS = 20_000
    }

    data class InputBlock(val id: Int, val text: String)
    data class Result(val id: Int, val translatedText: String)

    suspend fun translatePage(
        apiKey: String,
        blocks: List<InputBlock>,
        targetLanguage: String,
        seriesGlossary: Map<String, String> = emptyMap()
    ): List<Result>? = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isBlank() || blocks.isEmpty()) return@withContext null

        val glossaryText = if (seriesGlossary.isEmpty()) {
            "No glossary is available."
        } else {
            seriesGlossary.entries.joinToString("\n") { "${it.key} = ${it.value}" }
        }

        val items = JSONArray()
        blocks.forEach {
            items.put(JSONObject().put("id", it.id).put("text", it.text))
        }

        val prompt = """
            You are a professional manga/manhwa/manhua translator.
            Translate every dialogue/narration item into $targetLanguage.
            Preserve meaning, tone, honorific intent, character voice, and natural
            comic-style wording. Do not explain the translation.
            Keep names and recurring terms consistent with the glossary.
            Return ONLY a JSON array. Each item must contain id and translation.

            GLOSSARY:
            $glossaryText

            OCR ITEMS:
            $items
        """.trimIndent()

        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.25)
                    .put("responseMimeType", "application/json")
            )

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
            }

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode !in 200..299) return@withContext null

            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = JSONObject(response)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()
                ?: return@withContext null

            val jsonText = text
                .removePrefix("```json")
                .removePrefix(fence)
                .trim()

            val array = JSONArray(jsonText)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optInt("id", -1)
                    val translation = item.optString("translation", "").trim()
                    if (id >= 0 && translation.isNotBlank()) {
                        add(Result(id, translation))
                    }
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
