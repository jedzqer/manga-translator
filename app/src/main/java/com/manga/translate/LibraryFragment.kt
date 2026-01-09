package com.manga.translate

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.manga.translate.databinding.FragmentLibraryBinding
import kotlinx.coroutines.launch
import java.io.File

class LibraryFragment : Fragment() {
    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()
    private lateinit var repository: LibraryRepository
    private lateinit var translationPipeline: TranslationPipeline
    private val translationStore = TranslationStore()
    private val folderAdapter = LibraryFolderAdapter(
        onClick = { openFolder(it.folder) },
        onDelete = { confirmDeleteFolder(it.folder) }
    )
    private val imageAdapter = FolderImageAdapter(
        onSelectionChanged = { updateSelectionActions() },
        onItemLongPress = { enterSelectionMode(it.file) }
    )
    private var currentFolder: File? = null
    private var imageSelectionMode = false

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            addImagesToFolder(uris)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LibraryRepository(requireContext())
        translationPipeline = TranslationPipeline(requireContext())
        binding.folderList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderList.adapter = folderAdapter
        binding.folderImageList.layoutManager = LinearLayoutManager(requireContext())
        binding.folderImageList.adapter = imageAdapter

        binding.addFolderFab.setOnClickListener { showCreateFolderDialog() }
        binding.folderBackButton.setOnClickListener { showFolderList() }
        binding.folderAddImages.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        binding.folderTranslate.setOnClickListener { translateFolder() }
        binding.folderRead.setOnClickListener { startReading() }
        binding.folderSelectAll.setOnClickListener { toggleSelectAllImages() }
        binding.folderDeleteSelected.setOnClickListener { confirmDeleteSelectedImages() }
        binding.folderCancelSelection.setOnClickListener { exitSelectionMode() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (imageSelectionMode) {
                        exitSelectionMode()
                        return
                    }
                    if (binding.folderDetailContainer.visibility == View.VISIBLE) {
                        showFolderList()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        loadFolders()
        showFolderList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showFolderList() {
        currentFolder = null
        binding.libraryListContainer.visibility = View.VISIBLE
        binding.folderDetailContainer.visibility = View.GONE
        binding.addFolderFab.visibility = View.VISIBLE
        clearFolderStatus()
        exitSelectionMode()
        folderAdapter.clearDeleteSelection()
        loadFolders()
    }

    private fun showFolderDetail(folder: File) {
        currentFolder = folder
        binding.folderTitle.text = folder.name
        binding.libraryListContainer.visibility = View.GONE
        binding.folderDetailContainer.visibility = View.VISIBLE
        binding.addFolderFab.visibility = View.GONE
        exitSelectionMode()
        AppLogger.log("Library", "Opened folder ${folder.name}")
        loadImages(folder)
    }

    private fun loadFolders() {
        val folders = repository.listFolders()
        val items = folders.map { folder ->
            FolderItem(folder, repository.listImages(folder).size)
        }
        folderAdapter.submit(items)
        binding.libraryEmpty.text = getString(R.string.folder_empty)
        binding.libraryEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadImages(folder: File) {
        val images = repository.listImages(folder)
        val items = images.map { file ->
            ImageItem(file, translationStore.translationFileFor(file).exists())
        }
        imageAdapter.submit(items)
        binding.folderImagesEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (imageSelectionMode) {
            updateSelectionActions()
        } else {
            clearFolderStatus()
        }
    }

    private fun openFolder(folder: File) {
        showFolderDetail(folder)
    }

    private fun showCreateFolderDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.folder_name_hint)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_folder)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString().orEmpty()
                val folder = repository.createFolder(name)
                if (folder == null) {
                    AppLogger.log("Library", "Create folder failed: $name")
                    Toast.makeText(
                        requireContext(),
                        R.string.folder_create_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AppLogger.log("Library", "Created folder ${folder.name}")
                    loadFolders()
                }
            }
            .show()
    }

    private fun addImagesToFolder(uris: List<Uri>) {
        val folder = currentFolder ?: return
        val added = repository.addImages(folder, uris)
        AppLogger.log("Library", "Added ${added.size} images to ${folder.name}")
        loadImages(folder)
        loadFolders()
    }

    private fun confirmDeleteFolder(folder: File) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.folder_delete)
            .setMessage(getString(R.string.folder_delete_confirm, folder.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.folder_delete) { _, _ ->
                val deleted = repository.deleteFolder(folder)
                if (!deleted) {
                    AppLogger.log("Library", "Delete folder failed: ${folder.name}")
                    Toast.makeText(
                        requireContext(),
                        R.string.folder_delete_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AppLogger.log("Library", "Deleted folder ${folder.name}")
                }
                loadFolders()
            }
            .show()
    }

    private fun translateFolder() {
        val folder = currentFolder ?: return
        exitSelectionMode()
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        val llmClient = LlmClient(requireContext())
        if (!llmClient.isConfigured()) {
            setFolderStatus(getString(R.string.missing_api_settings))
            return
        }
        binding.folderTranslate.isEnabled = false
        AppLogger.log("Library", "Start translating folder ${folder.name}, ${images.size} images")
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            try {
                var translatedCount = 0
                setFolderStatus(
                    getString(R.string.folder_translation_progress, translatedCount, images.size),
                    getString(R.string.detecting_bubbles)
                )
                for (image in images) {
                    val result = try {
                        translationPipeline.translateImage(image) { progress ->
                            binding.folderProgressRight.post { binding.folderProgressRight.text = progress }
                        }
                    } catch (e: Exception) {
                        AppLogger.log("Library", "Translation failed for ${image.name}", e)
                        null
                    }
                    if (result != null) {
                        translationPipeline.saveResult(image, result)
                        translatedCount += 1
                    } else {
                        failed = true
                    }
                    setFolderStatus(
                        getString(R.string.folder_translation_progress, translatedCount, images.size),
                        if (translatedCount < images.size) getString(R.string.detecting_bubbles) else ""
                    )
                }
                setFolderStatus(
                    if (failed) getString(R.string.translation_failed) else getString(R.string.translation_done)
                )
                AppLogger.log(
                    "Library",
                    "Folder translation ${if (failed) "completed with failures" else "completed"}: ${folder.name}"
                )
                loadImages(folder)
            } finally {
                binding.folderTranslate.isEnabled = true
            }
        }
    }

    private fun startReading() {
        val folder = currentFolder ?: return
        exitSelectionMode()
        val images = repository.listImages(folder)
        if (images.isEmpty()) {
            setFolderStatus(getString(R.string.folder_images_empty))
            return
        }
        AppLogger.log("Library", "Start reading ${folder.name}, ${images.size} images")
        readingSessionViewModel.setFolder(folder, images)
        (activity as? MainActivity)?.switchToTab(MainPagerAdapter.READING_INDEX)
    }

    private fun enterSelectionMode(target: File) {
        if (!imageSelectionMode) {
            imageSelectionMode = true
            imageAdapter.setSelectionMode(true)
            binding.folderSelectionActions.visibility = View.VISIBLE
        }
        imageAdapter.toggleSelectionAndNotify(target)
        updateSelectionActions()
    }

    private fun exitSelectionMode() {
        if (!imageSelectionMode) return
        imageSelectionMode = false
        imageAdapter.setSelectionMode(false)
        binding.folderSelectionActions.visibility = View.GONE
        clearFolderStatus()
    }

    private fun updateSelectionActions() {
        if (!imageSelectionMode) return
        val count = imageAdapter.selectedCount()
        setFolderStatus(getString(R.string.folder_selection_count, count))
        val buttonText = if (imageAdapter.areAllSelected()) {
            getString(R.string.clear_all)
        } else {
            getString(R.string.select_all)
        }
        binding.folderSelectAll.text = buttonText
    }

    private fun toggleSelectAllImages() {
        if (!imageSelectionMode) return
        if (imageAdapter.areAllSelected()) {
            imageAdapter.clearSelection()
        } else {
            imageAdapter.selectAll()
        }
        updateSelectionActions()
    }

    private fun confirmDeleteSelectedImages() {
        val folder = currentFolder ?: return
        val selected = imageAdapter.getSelectedFiles()
        if (selected.isEmpty()) {
            setFolderStatus(getString(R.string.delete_images_empty))
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_selected)
            .setMessage(getString(R.string.delete_images_confirm, selected.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_selected) { _, _ ->
                var failed = false
                for (file in selected) {
                    if (!file.delete()) {
                        failed = true
                    }
                    translationStore.translationFileFor(file).delete()
                }
                if (failed) {
                    AppLogger.log("Library", "Delete selected images failed in ${folder.name}")
                    Toast.makeText(
                        requireContext(),
                        R.string.delete_images_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    AppLogger.log("Library", "Deleted ${selected.size} images from ${folder.name}")
                }
                exitSelectionMode()
                loadImages(folder)
                loadFolders()
            }
            .show()
    }

    private fun setFolderStatus(left: String, right: String = "") {
        binding.folderProgressLeft.text = left
        binding.folderProgressRight.text = right
    }

    private fun clearFolderStatus() {
        setFolderStatus("")
    }
}
