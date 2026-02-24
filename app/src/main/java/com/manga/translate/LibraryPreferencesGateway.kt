package com.manga.translate

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal class LibraryPreferencesGateway(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    fun isFullTranslateEnabled(folder: File): Boolean {
        return prefs.getBoolean(fullTranslateKeyPrefix + folder.absolutePath, true)
    }

    fun setFullTranslateEnabled(folder: File, enabled: Boolean) {
        prefs.edit() {putBoolean(fullTranslateKeyPrefix + folder.absolutePath, enabled)}
    }

    fun getTranslationLanguage(folder: File): TranslationLanguage {
        val value = prefs.getString(languageKeyPrefix + folder.absolutePath, null)
        return TranslationLanguage.fromString(value)
    }

    fun setTranslationLanguage(folder: File, language: TranslationLanguage) {
        prefs.edit() {putString(languageKeyPrefix + folder.absolutePath, language.name)}
    }

    fun getEhViewerTreeUri(): Uri? {
        return prefs.getString(ehViewerTreeKey, null)?.let(Uri::parse)
    }

    fun setEhViewerTreeUri(uri: Uri) {
        prefs.edit() {putString(ehViewerTreeKey, uri.toString())}
    }

    fun getExportTreeUri(): Uri? {
        return prefs.getString(exportTreeKey, null)?.let(Uri::parse)
    }

    fun setExportTreeUri(uri: Uri) {
        prefs.edit() {putString(exportTreeKey, uri.toString())}
    }

    fun hasEhViewerPermission(uri: Uri): Boolean {
        val persisted = context
            .contentResolver
            .persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
        val root = DocumentFile.fromTreeUri(context, uri)
        return persisted && root?.canRead() == true
    }

    fun hasExportPermission(uri: Uri): Boolean {
        val persisted = context
            .contentResolver
            .persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        val root = DocumentFile.fromTreeUri(context, uri)
        return persisted && root?.canWrite() == true
    }

    fun isEhViewerTree(uri: Uri): Boolean {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            docId.contains("EhViewer/download", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    fun buildEhViewerInitialUri(): Uri? {
        return try {
            DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:EhViewer/download"
            )
        } catch (_: Exception) {
            try {
                DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:EhViewer"
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun buildExportInitialUri(): Uri? {
        return try {
            DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Documents/manga-translator"
            )
        } catch (_: Exception) {
            try {
                DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Documents"
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private companion object {
        private const val ehViewerTreeKey = "ehviewer_tree_uri"
        private const val exportTreeKey = "export_tree_uri"
        private const val fullTranslateKeyPrefix = "full_translate_enabled_"
        private const val languageKeyPrefix = "translation_language_"
    }
}
