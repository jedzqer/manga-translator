package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.ImageView
import kotlin.math.abs

class ReadingImageTransformController(
    context: Context,
    private val imageView: ImageView,
    private val hasBubbleAt: (x: Float, y: Float) -> Boolean,
    private val onMatrixUpdated: () -> Unit
) {
    private val baseMatrix = Matrix()
    private val imageMatrix = Matrix()
    private val imageRect = RectF()
    private val panTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var imageUserScale = 1f
    private var minScale = 1f
    private var maxScale = 3f
    private var isScaling = false
    private var isPanning = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var startTouchX = 0f
    private var startTouchY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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
        }
    )

    private var currentBitmap: Bitmap? = null

    init {
        imageView.scaleType = ImageView.ScaleType.MATRIX
    }

    fun setCurrentBitmap(bitmap: Bitmap?) {
        currentBitmap = bitmap
    }

    fun reset(bitmap: Bitmap, mode: ReadingDisplayMode) {
        currentBitmap = bitmap
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val drawableWidth = bitmap.width.toFloat()
        val drawableHeight = bitmap.height.toFloat()
        val scale = when (mode) {
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

    fun handleTouch(event: MotionEvent): Boolean {
        val bitmap = currentBitmap ?: return false
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            return true
        }
        val zoomed = imageUserScale > minScale + 0.01f
        val overflow = isImageOverflowing(bitmap)
        val allowPan = (zoomed || overflow) && !hasBubbleAt(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = event.x
                startTouchY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                isPanning = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (isScaling) return true
                if (allowPan) {
                    val movedX = event.x - startTouchX
                    val movedY = event.y - startTouchY
                    if (!isPanning && (abs(movedX) > panTouchSlop || abs(movedY) > panTouchSlop)) {
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

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = isPanning || isScaling
                isPanning = false
                return handled
            }
        }
        return isScaling || isPanning
    }

    fun computeImageDisplayRect(): RectF? {
        val drawable = imageView.drawable ?: return null
        val rect = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )
        imageView.imageMatrix.mapRect(rect)
        rect.offset(imageView.left.toFloat(), imageView.top.toFloat())
        return rect
    }

    private fun applyImageMatrix() {
        imageView.imageMatrix = imageMatrix
        onMatrixUpdated()
    }

    private fun fixTranslation(bitmap: Bitmap) {
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
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

    private fun isImageOverflowing(bitmap: Bitmap): Boolean {
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return false
        imageRect.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        imageMatrix.mapRect(imageRect)
        return imageRect.width() > viewWidth + 0.5f || imageRect.height() > viewHeight + 0.5f
    }
}
