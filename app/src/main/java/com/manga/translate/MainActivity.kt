package com.manga.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import com.manga.translate.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var mangaOcr: MangaOcr? = null
    private var bubbleDetector: BubbleDetector? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            loadPreview(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.statusText.text = getString(R.string.welcome_message)
        binding.uploadButton.setOnClickListener {
            pickImage.launch("image/*")
        }
    }

    private fun loadPreview(uri: Uri) {
        binding.statusText.text = getString(R.string.loading_image)
        binding.previewImage.setImageDrawable(null)
        lifecycleScope.launch {
            val bitmap = loadBitmap(uri)
            if (bitmap == null) {
                binding.statusText.text = getString(R.string.load_image_failed)
                return@launch
            }
            binding.statusText.text = getString(R.string.detecting_bubbles)
            val pipelineResult = withContext(Dispatchers.Default) {
                runPipeline(bitmap) { progress ->
                    runOnUiThread {
                        binding.statusText.text = progress
                    }
                }
            }
            if (pipelineResult == null) {
                binding.previewImage.setImageBitmap(bitmap)
                binding.statusText.text = getString(R.string.ocr_failed)
                return@launch
            }
            binding.previewImage.setImageBitmap(pipelineResult.preview)
            binding.statusText.text = if (pipelineResult.count > 0) {
                getString(R.string.render_complete, pipelineResult.count)
            } else {
                getString(R.string.no_bubbles)
            }
        }
    }

    private fun getOcr(): MangaOcr? {
        if (mangaOcr != null) return mangaOcr
        return try {
            mangaOcr = MangaOcr(this)
            mangaOcr
        } catch (e: Exception) {
            null
        }
    }

    private fun getBubbleDetector(): BubbleDetector? {
        if (bubbleDetector != null) return bubbleDetector
        return try {
            bubbleDetector = BubbleDetector(this)
            bubbleDetector
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadBitmap(uri: Uri): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun runPipeline(bitmap: Bitmap, onProgress: (String) -> Unit): PipelineResult? {
        val detector = getBubbleDetector() ?: return null
        val ocr = getOcr() ?: return null
        val detections = detector.detect(bitmap)
        if (detections.isEmpty()) {
            return PipelineResult(bitmap, 0)
        }
        onProgress(getString(R.string.ocr_in_progress))
        val results = ArrayList<BubbleText>(detections.size)
        for (det in detections) {
            val crop = cropBitmap(bitmap, det.rect) ?: continue
            val text = ocr.recognize(crop).trim()
            if (text.isNotEmpty()) {
                results.add(BubbleText(det.rect, text))
            } else {
                results.add(BubbleText(det.rect, ""))
            }
        }
        val preview = drawResults(bitmap, results)
        return PipelineResult(preview, results.size)
    }

    private fun cropBitmap(source: Bitmap, rect: RectF): Bitmap? {
        val left = rect.left.toInt().coerceIn(0, source.width - 1)
        val top = rect.top.toInt().coerceIn(0, source.height - 1)
        val right = rect.right.toInt().coerceIn(1, source.width)
        val bottom = rect.bottom.toInt().coerceIn(1, source.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun drawResults(source: Bitmap, results: List<BubbleText>): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val strokePaint = Paint().apply {
            color = 0xFFFF3B30.toInt()
            style = Paint.Style.STROKE
            strokeWidth = (source.width.coerceAtLeast(source.height) / 200f).coerceAtLeast(2f)
            isAntiAlias = true
        }
        val fillPaint = Paint().apply {
            color = 0xCCFFFFFF.toInt()
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val textPaint = TextPaint().apply {
            color = 0xFF1B1B1B.toInt()
            isAntiAlias = true
        }
        for (result in results) {
            canvas.drawRect(result.rect, strokePaint)
            if (result.text.isNotBlank()) {
                val textBounds = RectF(result.rect)
                val pad = (textBounds.width().coerceAtMost(textBounds.height()) * 0.08f)
                    .coerceAtLeast(6f)
                textBounds.inset(pad, pad)
                canvas.drawRect(textBounds, fillPaint)
                drawTextInRect(canvas, result.text, textBounds, textPaint)
            }
        }
        return output
    }

    private fun drawTextInRect(canvas: Canvas, text: String, rect: RectF, paint: TextPaint) {
        val maxWidth = rect.width().toInt().coerceAtLeast(1)
        val maxHeight = rect.height().toInt().coerceAtLeast(1)
        var textSize = (rect.height() / 3f).coerceIn(12f, 42f)
        var layout = buildLayout(text, paint, maxWidth, textSize)
        while (layout.height > maxHeight && textSize > 10f) {
            textSize *= 0.9f
            layout = buildLayout(text, paint, maxWidth, textSize)
        }
        val dx = rect.left
        val dy = rect.top + ((rect.height() - layout.height) / 2f).coerceAtLeast(0f)
        canvas.save()
        canvas.translate(dx, dy)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int, textSize: Float): StaticLayout {
        paint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }
}

private data class BubbleText(
    val rect: RectF,
    val text: String
)

private data class PipelineResult(
    val preview: Bitmap,
    val count: Int
)
