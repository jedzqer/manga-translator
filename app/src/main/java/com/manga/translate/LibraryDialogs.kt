package com.manga.translate

import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile

internal class LibraryDialogs {
    fun showCreateFolderDialog(context: Context, onConfirm: (String) -> Unit) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.folder_name_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.create_folder)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirm(input.text?.toString().orEmpty())
            }
            .show()
    }

    fun confirmDeleteFolder(context: Context, folderName: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.folder_delete)
            .setMessage(context.getString(R.string.folder_delete_confirm, folderName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.folder_delete) { _, _ -> onConfirm() }
            .show()
    }

    fun showFullTranslateInfo(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.folder_full_translate_info_title)
            .setMessage(context.getString(R.string.folder_full_translate_info))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showLanguageSettingDialog(
        context: Context,
        currentLanguage: TranslationLanguage,
        onSelected: (TranslationLanguage) -> Unit
    ) {
        val languages = TranslationLanguage.values()
        val languageNames = languages.map { context.getString(it.displayNameResId) }.toTypedArray()
        val currentIndex = languages.indexOf(currentLanguage)

        AlertDialog.Builder(context)
            .setTitle(R.string.folder_language_setting_title)
            .setSingleChoiceItems(languageNames, currentIndex) { dialog, which ->
                onSelected(languages[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showApiErrorDialog(context: Context, errorCode: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.api_request_failed_title)
            .setMessage(context.getString(R.string.api_request_failed_message, errorCode))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showModelErrorDialog(context: Context, responseContent: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.model_response_failed_title)
            .setMessage(context.getString(R.string.model_response_failed_message, responseContent))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun showEhViewerSubfolderPicker(
        context: Context,
        folders: List<DocumentFile>,
        onPicked: (DocumentFile) -> Unit
    ) {
        val names = folders.map { it.name ?: "未命名" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(R.string.ehviewer_select_folder)
            .setItems(names) { _, index -> onPicked(folders[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun showEhViewerImportNameDialog(
        context: Context,
        defaultName: String,
        onConfirm: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.folder_name_hint)
            setText(defaultName)
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.ehviewer_import_name_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(context, R.string.folder_create_failed, Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(name)
                }
            }
            .show()
    }

    fun showExportSuccessDialog(context: Context, path: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.export_success_title)
            .setMessage(context.getString(R.string.export_success_message, path))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun confirmDeleteSelectedImages(
        context: Context,
        selectedCount: Int,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.delete_selected)
            .setMessage(context.getString(R.string.delete_images_confirm, selectedCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_selected) { _, _ -> onConfirm() }
            .show()
    }
}
