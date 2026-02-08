package com.manga.translate

import android.content.Context
import android.view.View
import android.widget.Toast
import com.manga.translate.databinding.FragmentLibraryBinding
import java.io.File

internal class LibrarySelectionController(
    private val imageAdapter: FolderImageAdapter,
    private val translationStore: TranslationStore,
    private val ocrStore: OcrStore,
    private val repository: LibraryRepository,
    private val ui: LibraryUiCallbacks,
    private val dialogs: LibraryDialogs,
    private val bindingProvider: () -> FragmentLibraryBinding?,
    private val contextProvider: () -> Context?,
    private val onRetranslateRequested: (File, List<File>, Boolean) -> Unit
) {
    var isSelectionMode: Boolean = false
        private set

    fun enterSelectionMode(target: File) {
        if (!isSelectionMode) {
            isSelectionMode = true
            imageAdapter.setSelectionMode(true)
            bindingProvider()?.folderSelectionActions?.visibility = View.VISIBLE
        }
        imageAdapter.toggleSelectionAndNotify(target)
        updateSelectionActions()
    }

    fun exitSelectionMode() {
        if (!isSelectionMode) return
        isSelectionMode = false
        imageAdapter.setSelectionMode(false)
        bindingProvider()?.folderSelectionActions?.visibility = View.GONE
        ui.clearFolderStatus()
    }

    fun updateSelectionActions() {
        if (!isSelectionMode) return
        val context = contextProvider() ?: return
        val count = imageAdapter.selectedCount()
        ui.setFolderStatus(context.getString(R.string.folder_selection_count, count))
        val buttonText = if (imageAdapter.areAllSelected()) {
            context.getString(R.string.clear_all)
        } else {
            context.getString(R.string.select_all)
        }
        bindingProvider()?.folderSelectAll?.text = buttonText
    }

    fun toggleSelectAllImages() {
        if (!isSelectionMode) return
        if (imageAdapter.areAllSelected()) {
            imageAdapter.clearSelection()
        } else {
            imageAdapter.selectAll()
        }
        updateSelectionActions()
    }

    fun confirmDeleteSelectedImages(folder: File?) {
        val context = contextProvider() ?: return
        if (folder == null) return
        val selected = imageAdapter.getSelectedFiles()
        if (selected.isEmpty()) {
            ui.setFolderStatus(context.getString(R.string.delete_images_empty))
            return
        }
        dialogs.confirmDeleteSelectedImages(context, selected.size) {
            var failed = false
            for (file in selected) {
                if (!file.delete()) {
                    failed = true
                }
                translationStore.translationFileFor(file).delete()
                ocrStore.ocrFileFor(file).delete()
            }
            if (failed) {
                AppLogger.log("Library", "Delete selected images failed in ${folder.name}")
                Toast.makeText(context, R.string.delete_images_failed, Toast.LENGTH_SHORT).show()
            } else {
                AppLogger.log("Library", "Deleted ${selected.size} images from ${folder.name}")
            }
            exitSelectionMode()
            ui.refreshImages(folder)
            ui.refreshFolders()
        }
    }

    fun retranslateSelectedImages(folder: File?, fullTranslateEnabled: Boolean) {
        val context = contextProvider() ?: return
        if (folder == null) return
        val selected = imageAdapter.getSelectedFiles()
        if (selected.isEmpty()) {
            ui.setFolderStatus(context.getString(R.string.retranslate_images_empty))
            return
        }
        exitSelectionMode()
        onRetranslateRequested(folder, selected, fullTranslateEnabled)
    }
}
