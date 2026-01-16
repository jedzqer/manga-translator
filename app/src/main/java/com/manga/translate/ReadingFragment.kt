package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.manga.translate.databinding.FragmentReadingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadingFragment : Fragment() {
    private var _binding: FragmentReadingBinding? = null
    private val binding get() = _binding!!
    private val readingSessionViewModel: ReadingSessionViewModel by activityViewModels()
    private val translationStore = TranslationStore()
    private lateinit var settingsStore: SettingsStore
    private lateinit var readingProgressStore: ReadingProgressStore
    private var currentImageFile: java.io.File? = null
    private var currentTranslation: TranslationResult? = null
    private var translationWatchJob: Job? = null
    private var currentBitmap: Bitmap? = null
    private val baseMatrix = Matrix()
    private val imageMatrix = Matrix()
    private val imageRect = RectF()
    private var imageUserScale = 1f
    private var minScale = 1f
    private var maxScale = 3f
    private var isScaling = false
    private var isPanning = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var panTouchSlop = 0f
    private lateinit var scaleDetector: ScaleGestureDetector
    private var readingDisplayMode = ReadingDisplayMode.FIT_WIDTH

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsStore = SettingsStore(requireContext())
        readingProgressStore = ReadingProgressStore(requireContext())
        readingDisplayMode = settingsStore.loadReadingDisplayMode()
        panTouchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop.toFloat()
        binding.readingImage.scaleType = ImageView.ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val bitmap = currentBitmap ?: return false
                val newScale = (imageUserScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = newScale / imageUserScale
                imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                imageUserScale = newScale
                fixTranslation(bitmap)
                applyImageMatrix()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })
        binding.translationOverlay.onTap = { x ->
            handleTap(x)
        }
        binding.translationOverlay.onSwipe = { direction ->
            handleSwipe(direction)
        }
        binding.translationOverlay.onTransformTouch = { event ->
            handleTransformTouch(event)
        }
        applyTextLayoutSetting()
        readingSessionViewModel.images.observe(viewLifecycleOwner) {
            loadCurrentImage()
        }
        readingSessionViewModel.index.observe(viewLifecycleOwner) {
            loadCurrentImage()
            persistReadingProgress()
        }
        binding.readingImage.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (currentBitmap == null) return@addOnLayoutChangeListener
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                resetImageMatrix(currentBitmap!!)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyTextLayoutSetting()
        applyReadingDisplayMode()
        (activity as? MainActivity)?.setPagerSwipeEnabled(false)
    }

    override fun onPause() {
        super.onPause()
        (activity as? MainActivity)?.setPagerSwipeEnabled(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        translationWatchJob?.cancel()
        _binding = null
    }

    private fun loadCurrentImage() {
        val images = readingSessionViewModel.images.value.orEmpty()
        val folder = readingSessionViewModel.currentFolder.value
        if (images.isEmpty() || folder == null) {
            binding.readingEmptyHint.visibility = View.VISIBLE
            binding.readingPageInfo.visibility = View.GONE
            binding.translationOverlay.visibility = View.GONE
            binding.readingImage.setImageDrawable(null)
            currentBitmap = null
            return
        }
        val index = (readingSessionViewModel.index.value ?: 0).coerceIn(0, images.lastIndex)
        val imageFile = images[index]
        currentImageFile = imageFile
        binding.readingEmptyHint.visibility = View.GONE
        binding.readingPageInfo.visibility = View.VISIBLE
        binding.readingPageInfo.text = getString(
            R.string.reading_page_info,
            folder.name,
            index + 1,
            images.size
        )
        val targetPath = imageFile.absolutePath
        val targetIndex = index
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = loadBitmap(imageFile.absolutePath)
            val translation = withContext(Dispatchers.IO) {
                translationStore.load(imageFile)
            }
            val currentImages = readingSessionViewModel.images.value.orEmpty()
            val currentIndex = readingSessionViewModel.index.value ?: 0
            if (currentIndex != targetIndex || currentImages.getOrNull(currentIndex)?.absolutePath != targetPath) {
                return@launch
            }
            if (bitmap != null) {
                binding.readingImage.setImageBitmap(bitmap)
                currentBitmap = bitmap
            } else {
                binding.readingImage.setImageDrawable(null)
                currentBitmap = null
            }
            binding.readingImage.post {
                if (bitmap != null) {
                    readingDisplayMode = settingsStore.loadReadingDisplayMode()
                    resetImageMatrix(bitmap)
                }
                updateOverlay(translation, bitmap)
            }
            if (translation == null && bitmap != null) {
                startTranslationWatcher(imageFile)
            } else {
                translationWatchJob?.cancel()
            }
        }
    }

    private fun updateOverlay(translation: TranslationResult?, bitmap: Bitmap?) {
        val rect = computeImageDisplayRect() ?: run {
            binding.translationOverlay.visibility = View.GONE
            return
        }
        val width = translation?.width ?: bitmap?.width ?: 0
        val height = translation?.height ?: bitmap?.height ?: 0
        if (width <= 0 || height <= 0) {
            binding.translationOverlay.visibility = View.GONE
            return
        }
        val normalized = when {
            translation == null -> TranslationResult("", width, height, emptyList())
            translation.width == width && translation.height == height -> translation
            else -> translation.copy(width = width, height = height)
        }
        currentTranslation = normalized
        binding.translationOverlay.setDisplayRect(rect)
        binding.translationOverlay.setTranslations(normalized)
        binding.translationOverlay.setOffsets(emptyMap())
        binding.translationOverlay.visibility = View.VISIBLE
    }

    private fun computeImageDisplayRect(): RectF? {
        val drawable = binding.readingImage.drawable ?: return null
        val rect = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )
        binding.readingImage.imageMatrix.mapRect(rect)
        rect.offset(binding.readingImage.left.toFloat(), binding.readingImage.top.toFloat())
        return rect
    }

    private fun applyImageMatrix() {
        binding.readingImage.imageMatrix = imageMatrix
        updateOverlayDisplayRect()
    }

    private fun updateOverlayDisplayRect() {
        if (binding.translationOverlay.visibility != View.VISIBLE) return
        val rect = computeImageDisplayRect() ?: return
        binding.translationOverlay.setDisplayRect(rect)
    }

    private fun resetImageMatrix(bitmap: Bitmap) {
        val viewWidth = binding.readingImage.width.toFloat()
        val viewHeight = binding.readingImage.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val drawableWidth = bitmap.width.toFloat()
        val drawableHeight = bitmap.height.toFloat()
        val scale = when (readingDisplayMode) {
            ReadingDisplayMode.FIT_WIDTH -> viewWidth / drawableWidth
            ReadingDisplayMode.FIT_HEIGHT -> viewHeight / drawableHeight
        }
        val dx = (viewWidth - drawableWidth * scale) / 2f
        val dy = (viewHeight - drawableHeight * scale) / 2f
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        imageMatrix.set(baseMatrix)
        imageUserScale = 1f
        minScale = 1f
        maxScale = 3f
        applyImageMatrix()
    }

    private fun fixTranslation(bitmap: Bitmap) {
        val viewWidth = binding.readingImage.width.toFloat()
        val viewHeight = binding.readingImage.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        imageRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        imageMatrix.mapRect(imageRect)
        var dx = 0f
        var dy = 0f
        if (imageRect.width() <= viewWidth) {
            dx = (viewWidth - imageRect.width()) / 2f - imageRect.left
        } else {
            if (imageRect.left > 0f) dx = -imageRect.left
            if (imageRect.right < viewWidth) dx = viewWidth - imageRect.right
        }
        if (imageRect.height() <= viewHeight) {
            dy = (viewHeight - imageRect.height()) / 2f - imageRect.top
        } else {
            if (imageRect.top > 0f) dy = -imageRect.top
            if (imageRect.bottom < viewHeight) dy = viewHeight - imageRect.bottom
        }
        imageMatrix.postTranslate(dx, dy)
    }

    private fun handleTransformTouch(event: android.view.MotionEvent): Boolean {
        val bitmap = currentBitmap ?: return false
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            return true
        }
        val zoomed = imageUserScale > minScale + 0.01f
        val overflow = isImageOverflowing(bitmap)
        val allowPan = (zoomed || overflow) && !binding.translationOverlay.hasBubbleAt(event.x, event.y)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                startTouchX = event.x
                startTouchY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                isPanning = false
                return false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (isScaling) return true
                if (allowPan) {
                    val movedX = event.x - startTouchX
                    val movedY = event.y - startTouchY
                    if (!isPanning && (kotlin.math.abs(movedX) > panTouchSlop || kotlin.math.abs(movedY) > panTouchSlop)) {
                        isPanning = true
                    }
                    if (isPanning) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        imageMatrix.postTranslate(dx, dy)
                        fixTranslation(bitmap)
                        applyImageMatrix()
                        lastTouchX = event.x
                        lastTouchY = event.y
                        return true
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                val handled = isPanning || isScaling
                isPanning = false
                return handled
            }
        }
        return isScaling || isPanning
    }

    private fun isImageOverflowing(bitmap: Bitmap): Boolean {
        val viewWidth = binding.readingImage.width.toFloat()
        val viewHeight = binding.readingImage.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return false
        imageRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        imageMatrix.mapRect(imageRect)
        return imageRect.width() > viewWidth + 0.5f || imageRect.height() > viewHeight + 0.5f
    }

    private fun applyReadingDisplayMode() {
        val mode = settingsStore.loadReadingDisplayMode()
        if (mode == readingDisplayMode) return
        readingDisplayMode = mode
        val bitmap = currentBitmap ?: return
        resetImageMatrix(bitmap)
        updateOverlay(currentTranslation, bitmap)
    }

    private suspend fun loadBitmap(path: String): Bitmap? = withContext(Dispatchers.IO) {
        android.graphics.BitmapFactory.decodeFile(path)
    }

    private fun handleTap(x: Float) {
        val width = binding.readingRoot.width
        if (width <= 0) return
        val ratio = x / width
        when {
            ratio < 0.33f -> {
                persistCurrentTranslation()
                readingSessionViewModel.prev()
            }
            ratio > 0.67f -> {
                persistCurrentTranslation()
                readingSessionViewModel.next()
            }
        }
    }

    private fun handleSwipe(direction: Int) {
        if (direction == 0) return
        persistCurrentTranslation()
        if (direction > 0) {
            readingSessionViewModel.prev()
        } else {
            readingSessionViewModel.next()
        }
    }

    private fun applyTextLayoutSetting() {
        val useHorizontal = settingsStore.loadUseHorizontalText()
        binding.translationOverlay.setVerticalLayoutEnabled(!useHorizontal)
    }

    private fun startTranslationWatcher(imageFile: java.io.File) {
        translationWatchJob?.cancel()
        translationWatchJob = viewLifecycleOwner.lifecycleScope.launch {
            val jsonFile = translationStore.translationFileFor(imageFile)
            while (isActive) {
                if (currentImageFile?.absolutePath != imageFile.absolutePath) return@launch
                if (jsonFile.exists()) {
                    val translation = withContext(Dispatchers.IO) {
                        translationStore.load(imageFile)
                    }
                    if (currentImageFile?.absolutePath != imageFile.absolutePath) return@launch
                    val bitmap = binding.readingImage.drawable?.let { _ ->
                        loadBitmap(imageFile.absolutePath)
                    }
                    if (bitmap != null) {
                        binding.readingImage.setImageBitmap(bitmap)
                        currentBitmap = bitmap
                    }
                    binding.readingImage.post {
                        updateOverlay(translation, bitmap)
                    }
                    return@launch
                }
                delay(800)
            }
        }
    }

    private fun persistReadingProgress() {
        val folder = readingSessionViewModel.currentFolder.value ?: return
        val index = readingSessionViewModel.index.value ?: return
        readingProgressStore.save(folder, index)
    }

    private fun persistCurrentTranslation() {
        val imageFile = currentImageFile ?: return
        val translation = currentTranslation ?: return
        val offsets = binding.translationOverlay.getOffsets()
        if (offsets.isEmpty()) return
        val updatedBubbles = translation.bubbles.map { bubble ->
            val offset = offsets[bubble.id] ?: (0f to 0f)
            bubble.copy(
                rect = RectF(
                    bubble.rect.left + offset.first,
                    bubble.rect.top + offset.second,
                    bubble.rect.right + offset.first,
                    bubble.rect.bottom + offset.second
                )
            )
        }
        val updated = translation.copy(bubbles = updatedBubbles)
        translationStore.save(imageFile, updated)
        currentTranslation = updated
        binding.translationOverlay.setTranslations(updated)
        binding.translationOverlay.setOffsets(emptyMap())
    }
}
