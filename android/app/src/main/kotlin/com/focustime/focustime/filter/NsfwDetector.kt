package com.matrixlab.focustime.filter

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * YOLO11-based NSFW object detector using EraX-Anti-NSFW-V1.1 (nano).
 *
 * Input:  [1, 320, 320, 3] float32, pixels normalised to [0,1]
 * Output: [1, 9, 2100]     float32
 *         rows 0-3 = cx, cy, w, h  (in pixels, 320-scale)
 *         rows 4-8 = class scores for anus, make_love, nipple, penis, vagina
 *
 * We transpose to [2100, 9] and apply confidence + NMS filtering.
 */
class NsfwDetector(context: Context) {

    companion object {
        private const val TAG = "NsfwDetector"
        private const val MODEL_FILE = "erax-anti-nsfw-yolo11n-v1.1_float32.tflite"
        private const val INPUT_SIZE = 320
        private const val NUM_DETECTIONS = 2100
        private const val NUM_CLASSES = 5
        private const val BOX_COORDS = 4
        // Detection thresholds
        private const val CONFIDENCE_THRESHOLD = 0.0025f
        private const val IOU_THRESHOLD = 0.45f
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null

    init {
        try {
            val model = loadModelFile(context)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                // Try GPU delegate first, then NNAPI, then CPU
                try {
                    if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                        gpuDelegate = GpuDelegate()
                        addDelegate(gpuDelegate)
                        Log.d(TAG, "Using GPU delegate")
                    } else {
                        throw UnsupportedOperationException("GPU not supported")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "GPU delegate unavailable: ${e.message}, trying NNAPI")
                    try {
                        nnApiDelegate = NnApiDelegate()
                        addDelegate(nnApiDelegate)
                        Log.d(TAG, "Using NNAPI delegate")
                    } catch (e2: Exception) {
                        Log.d(TAG, "NNAPI unavailable: ${e2.message}, using CPU")
                    }
                }
            }
            interpreter = Interpreter(model, options)
            Log.d(TAG, "NSFW YOLO detector loaded (input=${INPUT_SIZE}x${INPUT_SIZE}, detections=$NUM_DETECTIONS)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load NSFW detector model", e)
        }
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    /**
     * Returns true if any NSFW object is detected in the bitmap with confidence > threshold.
     * The bitmap is letterboxed to 320x320 internally (aspect-ratio preserved, gray padding).
     */
    fun detect(bitmap: Bitmap): Boolean {
        val interp = interpreter ?: return false
        return try {
            // 1. Letterbox to 320x320 (maintain aspect ratio, pad with gray)
            val inputBuffer = letterboxBitmapToByteBuffer(bitmap)

            // 2. Run inference — output shape [1, 9, 2100]
            val rawOutput = Array(1) { Array(BOX_COORDS + NUM_CLASSES) { FloatArray(NUM_DETECTIONS) } }
            interp.run(inputBuffer, rawOutput)

            // 3. Transpose to [2100, 9] and filter
            val detections = parseDetections(rawOutput[0])
            val nmsResults = nonMaxSuppression(detections)

            if (nmsResults.isNotEmpty()) {
                for (d in nmsResults) {
                    Log.d(TAG, "NSFW detected: class=${classLabel(d.classId)} conf=%.2f box=(%.0f,%.0f,%.0f,%.0f)".format(
                        d.confidence, d.cx, d.cy, d.w, d.h))
                }
            }

            // Analyze detected classes to decide safe/unsafe
            val detectedClasses = nmsResults.map { it.classId }.toSet()
            val hasMakeLove = 1 in detectedClasses       // make_love
            val hasBodyParts = detectedClasses.any { it in intArrayOf(0, 2, 3, 4) } // anus, nipple, penis, vagina

            val isUnsafe = when {
                hasBodyParts -> true                       // body parts detected → always unsafe
                hasMakeLove && !hasBodyParts -> false       // make_love alone → false positive
                else -> false                              // nothing detected
            }

            Log.d(TAG, "Result: ${if (isUnsafe) "UNSAFE" else "SAFE"} | " +
                    "detections=${nmsResults.size}, classes=${detectedClasses.map { classLabel(it) }}, " +
                    "makeLoveAlone=${hasMakeLove && !hasBodyParts}")

            isUnsafe
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            false
        }
    }

    private data class Detection(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val classId: Int, val confidence: Float
    )

    /**
     * Transpose [9, 2100] to list of detections, filtering by confidence.
     * Logs detailed statistics: count, min, max, average score per class.
     */
    private fun parseDetections(output: Array<FloatArray>): List<Detection> {
        val results = mutableListOf<Detection>()
        
        // Track statistics per class
        val classCount = IntArray(NUM_CLASSES)
        val classScoreSum = FloatArray(NUM_CLASSES)
        val classScoreMax = FloatArray(NUM_CLASSES)
        val classScoreMin = FloatArray(NUM_CLASSES) { Float.MAX_VALUE }
        
        for (i in 0 until NUM_DETECTIONS) {
            // Find best class score
            var maxScore = 0f
            var maxClass = 0
            for (c in 0 until NUM_CLASSES) {
                val score = output[BOX_COORDS + c][i]
                if (score > maxScore) {
                    maxScore = score
                    maxClass = c
                }
            }
            
            // Track statistics for all classes
            for (c in 0 until NUM_CLASSES) {
                val score = output[BOX_COORDS + c][i]
                classCount[c]++
                classScoreSum[c] += score
                classScoreMax[c] = maxOf(classScoreMax[c], score)
                classScoreMin[c] = minOf(classScoreMin[c], score)
            }
            
            if (maxScore >= CONFIDENCE_THRESHOLD) {
                results.add(Detection(
                    cx = output[0][i],
                    cy = output[1][i],
                    w = output[2][i],
                    h = output[3][i],
                    classId = maxClass,
                    confidence = maxScore
                ))
            }
        }
        
        // Log statistics for all classes
        Log.d(TAG, "=== NSFW Detection Statistics ===")
        for (c in 0 until NUM_CLASSES) {
            val count = classCount[c]
            val sum = classScoreSum[c]
            val max = classScoreMax[c]
            val min = if (classScoreMin[c] == Float.MAX_VALUE) 0f else classScoreMin[c]
            val avg = sum / count
            Log.d(TAG, "Class ${classLabel(c)}: count=$count, avg=%.4f, min=%.4f, max=%.4f, sum=%.2f".format(
                avg, min, max, sum))
        }
        Log.d(TAG, "Detections PASSED threshold ($CONFIDENCE_THRESHOLD): ${results.size} / $NUM_DETECTIONS")
        
        return results
    }

    /**
     * Simple greedy NMS by class.
     */
    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { iou(best, it) > IOU_THRESHOLD && best.classId == it.classId }
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val aLeft = a.cx - a.w / 2; val aTop = a.cy - a.h / 2
        val aRight = a.cx + a.w / 2; val aBot = a.cy + a.h / 2
        val bLeft = b.cx - b.w / 2; val bTop = b.cy - b.h / 2
        val bRight = b.cx + b.w / 2; val bBot = b.cy + b.h / 2

        val interLeft = maxOf(aLeft, bLeft); val interTop = maxOf(aTop, bTop)
        val interRight = minOf(aRight, bRight); val interBot = minOf(aBot, bBot)

        val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBot - interTop)
        val aArea = a.w * a.h
        val bArea = b.w * b.h
        val unionArea = aArea + bArea - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    /**
     * Letterbox the bitmap to INPUT_SIZE x INPUT_SIZE:
     * - Resize maintaining aspect ratio
     * - Pad remaining space with gray (114/255 ≈ 0.447)
     * - Normalize pixels to [0,1] float32, RGB order
     */
    private fun letterboxBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = minOf(INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH)
        val newW = (srcW * scale).toInt()
        val newH = (srcH * scale).toInt()
        val padLeft = (INPUT_SIZE - newW) / 2
        val padTop = (INPUT_SIZE - newH) / 2

        Log.d(TAG, "Letterbox: ${srcW}x${srcH} -> ${newW}x${newH}, pad=($padLeft,$padTop)")

        // Resize maintaining aspect ratio
        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val resizedPixels = IntArray(newW * newH)
        resized.getPixels(resizedPixels, 0, newW, 0, 0, newW, newH)
        if (resized != bitmap) resized.recycle()

        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())

        val grayNorm = 114f / 255f // Standard YOLO letterbox padding

        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val inImage = x >= padLeft && x < padLeft + newW &&
                              y >= padTop && y < padTop + newH
                if (inImage) {
                    val pixel = resizedPixels[(y - padTop) * newW + (x - padLeft)]
                    buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
                    buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
                    buffer.putFloat((pixel and 0xFF) / 255.0f)          // B
                } else {
                    buffer.putFloat(grayNorm) // R
                    buffer.putFloat(grayNorm) // G
                    buffer.putFloat(grayNorm) // B
                }
            }
        }
        return buffer
    }

    private fun classLabel(id: Int): String = when (id) {
        0 -> "anus"
        1 -> "make_love"
        2 -> "nipple"
        3 -> "penis"
        4 -> "vagina"
        else -> "unknown"
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        nnApiDelegate?.close()
        nnApiDelegate = null
        Log.d(TAG, "NsfwDetector closed")
    }
}
