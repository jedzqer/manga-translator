package com.manga.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.sqrt

class TextMaskDetector(
    context: Context,
    modelAssetName: String = "ysgyolo_1.2_OS1.0.onnx"
) {
    private val detector = YsgYoloTextDetector(context, modelAssetName)

    fun detectMask(bitmap: Bitmap): BooleanArray {
        if (bitmap.width <= 1 || bitmap.height <= 1) {
            return BooleanArray(bitmap.width * bitmap.height)
        }
        val detections = detector.detect(
            bitmap = bitmap,
            confThreshold = YSG_CONF_THRESHOLD,
            iouThreshold = YSG_NMS_IOU_THRESHOLD
        )
        if (detections.isEmpty()) {
            return BooleanArray(bitmap.width * bitmap.height)
        }

        val width = bitmap.width
        val height = bitmap.height
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(maskBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        for (detection in detections) {
            val corners = expandCorners(detection.corners, width, height)
            val path = Path().apply {
                moveTo(corners[0], corners[1])
                lineTo(corners[2], corners[3])
                lineTo(corners[4], corners[5])
                lineTo(corners[6], corners[7])
                close()
            }
            canvas.drawPath(path, paint)
        }
        val mask = BooleanArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                mask[row + x] = (maskBitmap.getPixel(x, y) ushr 24) > 0
            }
        }
        maskBitmap.recycle()
        val refined = refineMaskWithTextPixels(bitmap, mask, width, height)
        return dilateMask(refined, width, height, GLOBAL_DILATE_ITERATIONS)
    }

    private fun expandCorners(corners: FloatArray, width: Int, height: Int): FloatArray {
        if (corners.size < 8) return corners
        var cx = 0f
        var cy = 0f
        for (i in corners.indices step 2) {
            cx += corners[i]
            cy += corners[i + 1]
        }
        cx /= 4f
        cy /= 4f
        val aabb = RectF(
            minOf(corners[0], corners[2], corners[4], corners[6]),
            minOf(corners[1], corners[3], corners[5], corners[7]),
            maxOf(corners[0], corners[2], corners[4], corners[6]),
            maxOf(corners[1], corners[3], corners[5], corners[7])
        )
        val base = max(1f, minOf(aabb.width(), aabb.height()))
        val pad = max(MASK_BOX_EXPAND_MIN_PX, base * MASK_BOX_EXPAND_RATIO)
        val out = FloatArray(8)
        for (i in corners.indices step 2) {
            val x = corners[i]
            val y = corners[i + 1]
            val vx = x - cx
            val vy = y - cy
            val len = sqrt(vx * vx + vy * vy).coerceAtLeast(1e-6f)
            out[i] = (x + (vx / len) * pad).coerceIn(0f, width - 1f)
            out[i + 1] = (y + (vy / len) * pad).coerceIn(0f, height - 1f)
        }
        return out
    }

    private fun dilateMask(mask: BooleanArray, width: Int, height: Int, iterations: Int): BooleanArray {
        var current = mask
        repeat(iterations.coerceAtLeast(1)) {
            val out = current.clone()
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    if (!current[rowOffset + x]) continue
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until height) continue
                        val nOffset = ny * width
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx !in 0 until width) continue
                            out[nOffset + nx] = true
                        }
                    }
                }
            }
            current = out
        }
        return current
    }

    private fun refineMaskWithTextPixels(
        bitmap: Bitmap,
        candidate: BooleanArray,
        width: Int,
        height: Int
    ): BooleanArray {
        val refined = BooleanArray(candidate.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val idx = row + x
                if (!candidate[idx]) continue
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val maxRgb = maxOf(r, g, b)
                val minRgb = minOf(r, g, b)
                val spread = maxRgb - minRgb
                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                // Preserve likely text/outline pixels and drop near-white bubble background.
                if (luma <= TEXT_PIXEL_MAX_LUMA || spread >= TEXT_PIXEL_MIN_SPREAD) {
                    refined[idx] = true
                }
            }
        }
        return refined
    }

    companion object {
        private const val YSG_CONF_THRESHOLD = 0.4f
        private const val YSG_NMS_IOU_THRESHOLD = 0.6f
        private const val MASK_BOX_EXPAND_RATIO = 0.04f
        private const val MASK_BOX_EXPAND_MIN_PX = 1f
        private const val GLOBAL_DILATE_ITERATIONS = 1
        private const val TEXT_PIXEL_MAX_LUMA = 236f
        private const val TEXT_PIXEL_MIN_SPREAD = 16
    }
}
