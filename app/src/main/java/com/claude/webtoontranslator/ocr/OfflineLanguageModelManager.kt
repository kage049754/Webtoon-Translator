package com.claude.webtoontranslator.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.tasks.await

/**
 * Manages source-language models used for offline translation into English.
 * English is the target language and does not require a downloadable model.
 */
class OfflineLanguageModelManager {
    data class LanguageOption(val code: String, val label: String, val mlKitCode: String)

    val languages = listOf(
        LanguageOption("zh", "Chinese", TranslateLanguage.CHINESE),
        LanguageOption("ja", "Japanese", TranslateLanguage.JAPANESE),
        LanguageOption("ko", "Korean", TranslateLanguage.KOREAN),
        LanguageOption("es", "Spanish", TranslateLanguage.SPANISH),
        LanguageOption("fr", "French", TranslateLanguage.FRENCH)
    )

    private val remoteModelManager = RemoteModelManager.getInstance()

    private fun model(language: LanguageOption) =
        TranslateRemoteModel.Builder(language.mlKitCode).build()

    suspend fun installedLanguages(): Set<String> {
        return languages.filter { language ->
            try {
                remoteModelManager.isModelDownloaded(model(language)).await()
            } catch (_: Exception) {
                false
            }
        }.map { it.code }.toSet()
    }

    suspend fun install(languageCode: String) {
        val language = languages.firstOrNull { it.code == languageCode }
            ?: throw IllegalArgumentException("Unsupported offline language")
        remoteModelManager.download(
            model(language),
            DownloadConditions.Builder().build()
        ).await()
    }

    suspend fun remove(languageCode: String) {
        val language = languages.firstOrNull { it.code == languageCode }
            ?: throw IllegalArgumentException("Unsupported offline language")
        remoteModelManager.deleteDownloadedModel(model(language)).await()
    }
}
