package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class TextDetection(
    val score: Float,
    val classId: Int,
    val corners: FloatArray,
    val aabb: RectF
)

class YsgYoloTextDetector(
    private val context: Context,
    private val modelAssetName: String = "ysgyolo_1.2_OS1.0.onnx"
) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSession()
    private val inputName: String
    private val inputWidth: Int
    private val inputHeight: Int

    init {
        val input = session.inputInfo.entries.first()
        inputName = input.key
        val shape = (input.value.info as TensorInfo).shape
        inputHeight = (shape.getOrNull(2) ?: 640L).toInt().coerceAtLeast(1)
        inputWidth = (shape.getOrNull(3) ?: 640L).toInt().coerceAtLeast(1)
    }

    fun detect(
        bitmap: Bitmap,
        confThreshold: Float = DEFAULT_CONF_THRESHOLD,
        iouThreshold: Float = DEFAULT_NMS_IOU_THRESHOLD,
        allowedClassIds: Set<Int>? = null
    ): List<TextDetection> {
        if (bitmap.width <= 1 || bitmap.height <= 1) return emptyList()
        val pre = preprocess(bitmap)
        pre.tensor.use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output = outputs[0]
                val shape = (output.info as? TensorInfo)?.shape ?: return emptyList()
                val decoded = parseRawDetections(output.value, shape, confThreshold, allowedClassIds)
                return mapAndNms(decoded, pre, bitmap.width, bitmap.height, iouThreshold)
            }
        }
    }

    private fun preprocess(bitmap: Bitmap): PreprocessResult {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val gain = min(inputWidth.toFloat() / srcW, inputHeight.toFloat() / srcH).coerceAtLeast(1e-6f)
        val newW = (srcW * gain).toInt().coerceAtLeast(1)
        val newH = (srcH * gain).toInt().coerceAtLeast(1)

        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val padded = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val padX = ((inputWidth - newW) / 2f).coerceAtLeast(0f)
        val padY = ((inputHeight - newH) / 2f).coerceAtLeast(0f)
        canvas.drawBitmap(resized, padX, padY, null)
        if (resized !== bitmap) {
            resized.recycle()
        }

        val input = FloatArray(3 * inputWidth * inputHeight)
        var offset = 0
        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val pixel = padded.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                input[offset] = r
                input[offset + inputWidth * inputHeight] = g
                input[offset + 2 * inputWidth * inputHeight] = b
                offset++
            }
        }
        padded.recycle()

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        )
        return PreprocessResult(tensor, gain, padX, padY, inputWidth, inputHeight)
    }

    private fun parseRawDetections(
        raw: Any,
        shape: LongArray,
        confThreshold: Float,
        allowedClassIds: Set<Int>?
    ): List<RawDetection> {
        if (shape.size != 3) return emptyList()
        val batch = raw as? Array<*> ?: return emptyList()
        val first = batch.firstOrNull() as? Array<*> ?: return emptyList()
        val rows = first.mapNotNull { it as? FloatArray }
        if (rows.size != first.size) return emptyList()

        val detections = ArrayList<RawDetection>()
        val dim1 = shape[1].toInt()
        val dim2 = shape[2].toInt()
        if (dim1 <= 0 || dim2 <= 0) return emptyList()

        if (dim1 <= dim2) {
            val n = dim2
            val c = dim1
            if (c < 6) return emptyList()
            if (rows.any { it.size < n }) return emptyList()
            for (i in 0 until n) {
                val detection = readDetectionFromColumn(rows, i, c) ?: continue
                if (detection.score < confThreshold) continue
                if (allowedClassIds != null && !allowedClassIds.contains(detection.classId)) continue
                detections.add(detection)
            }
        } else {
            val n = dim1
            val c = dim2
            if (c < 6 || rows.size < n) return emptyList()
            for (i in 0 until n) {
                val row = rows[i]
                if (row.size < c) continue
                val detection = readDetectionFromRow(row, c) ?: continue
                if (detection.score < confThreshold) continue
                if (allowedClassIds != null && !allowedClassIds.contains(detection.classId)) continue
                detections.add(detection)
            }
        }
        return detections
    }

    private fun readDetectionFromColumn(rows: List<FloatArray>, index: Int, featureCount: Int): RawDetection? {
        if (featureCount < 6) return null
        val cx = rows[0][index]
        val cy = rows[1][index]
        val w = rows[2][index]
        val h = rows[3][index]
        val angle = rows[featureCount - 1][index]
        val classScores = featureCount - 5
        var bestScore = Float.NEGATIVE_INFINITY
        var bestClass = 0
        for (i in 0 until classScores) {
            val score = rows[4 + i][index]
            if (score > bestScore) {
                bestScore = score
                bestClass = i
            }
        }
        if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || !angle.isFinite()) return null
        return RawDetection(cx, cy, max(0f, w), max(0f, h), angle, bestClass, bestScore)
    }

    private fun readDetectionFromRow(row: FloatArray, featureCount: Int): RawDetection? {
        if (featureCount < 6) return null
        val cx = row[0]
        val cy = row[1]
        val w = row[2]
        val h = row[3]
        val angle = row[featureCount - 1]
        val classScores = featureCount - 5
        var bestScore = Float.NEGATIVE_INFINITY
        var bestClass = 0
        for (i in 0 until classScores) {
            val score = row[4 + i]
            if (score > bestScore) {
                bestScore = score
                bestClass = i
            }
        }
        if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || !angle.isFinite()) return null
        return RawDetection(cx, cy, max(0f, w), max(0f, h), angle, bestClass, bestScore)
    }

    private fun mapAndNms(
        detections: List<RawDetection>,
        pre: PreprocessResult,
        originalWidth: Int,
        originalHeight: Int,
        iouThreshold: Float
    ): List<TextDetection> {
        if (detections.isEmpty()) return emptyList()
        val mapped = detections.mapNotNull { det ->
            val corners = toCorners(det.cx, det.cy, det.w, det.h, det.angle)
            for (i in corners.indices step 2) {
                corners[i] = ((corners[i] - pre.padX) / pre.gain).coerceIn(0f, max(0f, originalWidth - 1f))
                corners[i + 1] = ((corners[i + 1] - pre.padY) / pre.gain).coerceIn(0f, max(0f, originalHeight - 1f))
            }
            val aabb = cornersToAabb(corners)
            if (aabb.width() < MIN_AABB_SIZE || aabb.height() < MIN_AABB_SIZE) return@mapNotNull null
            TextDetection(
                score = det.score,
                classId = det.classId,
                corners = corners,
                aabb = aabb
            )
        }.sortedByDescending { it.score }

        if (mapped.isEmpty()) return emptyList()
        val kept = ArrayList<TextDetection>(mapped.size)
        val removed = BooleanArray(mapped.size)
        for (i in mapped.indices) {
            if (removed[i]) continue
            val current = mapped[i]
            kept.add(current)
            for (j in i + 1 until mapped.size) {
                if (removed[j]) continue
                if (iou(current.aabb, mapped[j].aabb) > iouThreshold) {
                    removed[j] = true
                }
            }
        }
        return kept
    }

    private fun toCorners(cx: Float, cy: Float, w: Float, h: Float, angle: Float): FloatArray {
        val cosv = cos(angle)
        val sinv = sin(angle)
        val v1x = (w / 2f) * cosv
        val v1y = (w / 2f) * sinv
        val v2x = -(h / 2f) * sinv
        val v2y = (h / 2f) * cosv

        return floatArrayOf(
            cx + v1x + v2x, cy + v1y + v2y,
            cx + v1x - v2x, cy + v1y - v2y,
            cx - v1x - v2x, cy - v1y - v2y,
            cx - v1x + v2x, cy - v1y + v2y
        )
    }

    private fun cornersToAabb(corners: FloatArray): RectF {
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (i in corners.indices step 2) {
            val x = corners[i]
            val y = corners[i + 1]
            left = min(left, x)
            top = min(top, y)
            right = max(right, x)
            bottom = max(bottom, y)
        }
        return RectF(left, top, right, bottom)
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun createSession(): OrtSession {
        val modelFile = File(context.cacheDir, modelAssetName)
        if (!modelFile.exists()) {
            context.assets.open(modelAssetName).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    }

    private data class PreprocessResult(
        val tensor: OnnxTensor,
        val gain: Float,
        val padX: Float,
        val padY: Float,
        val inputWidth: Int,
        val inputHeight: Int
    )

    private data class RawDetection(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val angle: Float,
        val classId: Int,
        val score: Float
    )

    companion object {
        const val DEFAULT_CONF_THRESHOLD = 0.4f
        const val DEFAULT_NMS_IOU_THRESHOLD = 0.6f
        private const val MIN_AABB_SIZE = 2f
    }
}
