package com.matrixlab.focustime.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
class NsfwDetector(private val context: Context) {

    companion object {
        private const val TAG = "NsfwDetector"
        private const val MODEL_FILE = "erax-anti-nsfw-yolo11n-v1.1_float32.tflite"
        private const val INPUT_SIZE = 320
        private const val NUM_DETECTIONS = 2100
        private const val NUM_CLASSES = 5
        private const val BOX_COORDS = 4
        // Per-class detection thresholds
        private val CLASS_THRESHOLDS = floatArrayOf(
            0.05f,  // anus
            0.25f,  // make_love
            0.05f,  // nipple
            0.05f,  // penis
            0.05f   // vagina
        )
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

    data class DetectionResult(
        val isUnsafe: Boolean,
        val detections: List<Detection>,       // NMS-filtered, above threshold
        val allDetections: List<Detection>,    // Top raw detections for debug drawing
        val detectedClasses: Set<Int>
    )

    /**
     * Returns DetectionResult with NSFW analysis and all detection boxes.
     * The bitmap is letterboxed to 320x320 internally (aspect-ratio preserved, gray padding).
     */
    fun detect(bitmap: Bitmap): DetectionResult {
        val interp = interpreter ?: return DetectionResult(false, emptyList(), emptyList(), emptySet())
        return try {
            // 1. Letterbox to 320x320 (maintain aspect ratio, pad with gray)
            val inputBuffer = letterboxBitmapToByteBuffer(bitmap)

            // 2. Run inference — output shape [1, 9, 2100]
            val rawOutput = Array(1) { Array(BOX_COORDS + NUM_CLASSES) { FloatArray(NUM_DETECTIONS) } }
            interp.run(inputBuffer, rawOutput)

            // 3. Transpose to [2100, 9] and filter
            val (filtered, topAll) = parseDetections(rawOutput[0])
            val nmsResults = nonMaxSuppression(filtered)

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

            DetectionResult(isUnsafe, nmsResults, topAll, detectedClasses)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            DetectionResult(false, emptyList(), emptyList(), emptySet())
        }
    }

    /**
     * DEBUG: Draw bounding boxes on the original bitmap and save to app-specific directory.
     * Boxes are scaled from model coords (320x320 letterbox) back to original image coords.
     * Saves to: /storage/emulated/0/Android/data/com.matrixlab.focustime/files/blocked/
     */
    fun saveDebugImage(original: Bitmap, result: DetectionResult) {
        try {
            val dir = File(context.getExternalFilesDir("blocked"), "")
            dir.mkdirs()

            val mutable = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutable)

            // Calculate letterbox params to map boxes back to original coords
            val srcW = original.width.toFloat()
            val srcH = original.height.toFloat()
            val scale = minOf(INPUT_SIZE / srcW, INPUT_SIZE / srcH)
            val padLeft = (INPUT_SIZE - srcW * scale) / 2f
            val padTop = (INPUT_SIZE - srcH * scale) / 2f

            val classColors = intArrayOf(
                Color.RED,          // anus
                Color.MAGENTA,      // make_love
                Color.YELLOW,       // nipple
                Color.CYAN,         // penis
                Color.GREEN         // vagina
            )

            val boxPaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 36f
                isFakeBoldText = true
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }

            // Draw ALL top detections (including low-confidence) so we always see boxes
            for (d in result.allDetections) {
                // Convert from model 320x320 letterbox coords to original image coords
                val x1 = ((d.cx - d.w / 2f) - padLeft) / scale
                val y1 = ((d.cy - d.h / 2f) - padTop) / scale
                val x2 = ((d.cx + d.w / 2f) - padLeft) / scale
                val y2 = ((d.cy + d.h / 2f) - padTop) / scale

                boxPaint.color = classColors.getOrElse(d.classId) { Color.WHITE }
                canvas.drawRect(x1, y1, x2, y2, boxPaint)

                val label = "${classLabel(d.classId)} %.2f%%".format(d.confidence * 100)
                canvas.drawText(label, x1 + 4, y1 - 8, textPaint)
            }

            // Add result text at top
            val resultLabel = if (result.isUnsafe) "UNSAFE" else "SAFE"
            val headerPaint = Paint().apply {
                color = if (result.isUnsafe) Color.RED else Color.GREEN
                textSize = 48f
                isFakeBoldText = true
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }
            canvas.drawText("$resultLabel | ${result.detections.size} detections", 20f, 60f, headerPaint)

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "${resultLabel}_${timestamp}.jpg")
            FileOutputStream(file).use { out ->
                mutable.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            mutable.recycle()
            Log.d(TAG, "Debug image saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug image", e)
        }
    }

    data class Detection(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val classId: Int, val confidence: Float
    )

    /**
     * Transpose [9, 2100] to list of detections, filtering by confidence.
     * Returns Pair(filtered detections, top-20 raw detections for debug).
     */
    private fun parseDetections(output: Array<FloatArray>): Pair<List<Detection>, List<Detection>> {
        val results = mutableListOf<Detection>()
        val allCandidates = mutableListOf<Detection>()
        
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

            val det = Detection(
                cx = output[0][i],
                cy = output[1][i],
                w = output[2][i],
                h = output[3][i],
                classId = maxClass,
                confidence = maxScore
            )
            allCandidates.add(det)
            
            val threshold = CLASS_THRESHOLDS.getOrElse(maxClass) { 0.05f }
            if (maxScore >= threshold) {
                results.add(det)
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
        Log.d(TAG, "Detections PASSED per-class thresholds: ${results.size} / $NUM_DETECTIONS")

        // Return top 20 by confidence for debug drawing
        val top20 = allCandidates.sortedByDescending { it.confidence }.take(20)
        return Pair(results, top20)
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
