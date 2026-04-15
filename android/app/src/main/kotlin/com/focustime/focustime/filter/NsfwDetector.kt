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
        private const val BODY_PARTS = 0.1f
        // Per-class detection thresholds
        private val CLASS_THRESHOLDS = floatArrayOf(
            BODY_PARTS,  // anus
            0.30f,  // make_love
            BODY_PARTS,  // nipple
            BODY_PARTS,  // penis
            BODY_PARTS   // vagina
        )
        private const val IOU_THRESHOLD = 0.45f
        // Minimum box area as fraction of model input area (320×320).
        // Boxes smaller than this are noise anchors — real detections are larger.
        private const val MIN_BOX_AREA_FRACTION = 0.005f // 0.5% of 320×320 = ~512 px²
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

            // if (nmsResults.isNotEmpty()) {
            //     for (d in nmsResults) {
            //         Log.d(TAG, "NSFW detected: class=${classLabel(d.classId)} conf=%.2f box=(%.0f,%.0f,%.0f,%.0f)".format(
            //             d.confidence, d.cx, d.cy, d.w, d.h))
            //     }
            // }

            // Analyze detected classes to decide safe/unsafe
            val detectedClasses = nmsResults.map { it.classId }.toSet()
            val hasMakeLove = 1 in detectedClasses       // make_love
            val hasBodyParts = detectedClasses.any { it in intArrayOf(0, 2, 3, 4) } // anus, nipple, penis, vagina

            val isUnsafe = when {
                hasBodyParts -> true                       // body parts detected → always unsafe
                hasMakeLove && !hasBodyParts -> false       // make_love alone → false positive
                else -> false                              // nothing detected
            }

            // Log.d(TAG, "Result: ${if (isUnsafe) "UNSAFE" else "SAFE"} | " +
            //         "detections=${nmsResults.size}, classes=${detectedClasses.map { classLabel(it) }}, " +
            //         "makeLoveAlone=${hasMakeLove && !hasBodyParts}")

            DetectionResult(isUnsafe, nmsResults, topAll, detectedClasses)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            DetectionResult(false, emptyList(), emptyList(), emptySet())
        }
    }

    /**
     * Tiled detection: splits the bitmap into the minimum number of square
     * tiles (side = min(width, height)) with ≥10% overlap so each tile
     * resizes to 320×320 with zero aspect-ratio distortion (square→square).
     *
     * Example: 1080×2400 → 3 tiles of 1080×1080, stride ≈ 660.
     * Early-exits on the first unsafe tile.
     */
    fun detectTiled(bitmap: Bitmap): DetectionResult {
        interpreter ?: return DetectionResult(false, emptyList(), emptyList(), emptySet())

        val w = bitmap.width
        val h = bitmap.height
        val tileSize = minOf(w, h)

        // Already square or nearly so (≤15% longer) — skip tiling
        if (maxOf(w, h).toFloat() / tileSize <= 1.15f) {
            val result = detect(bitmap)
            // if (result.isUnsafe) saveUnsafeCrops(bitmap, result)
            return result
        }

        val isPortrait = h > w
        val longDim = maxOf(w, h)

        // Minimum tiles to cover the long axis with ≥10% overlap
        val maxStride = tileSize - (tileSize * 0.10f).toInt()
        val numTiles = ((longDim - tileSize + maxStride - 1) / maxStride) + 1

        // Space tiles evenly for uniform overlap
        val stride = if (numTiles > 1) (longDim - tileSize) / (numTiles - 1) else 0

        Log.d(TAG, "Tiled scan: ${w}x${h} → $numTiles tiles of ${tileSize}x${tileSize}, stride=$stride")

        val allNms = mutableListOf<Detection>()
        val allDebug = mutableListOf<Detection>()
        val allClasses = mutableSetOf<Int>()

        for (i in 0 until numTiles) {
            val offset = i * stride
            val x = if (isPortrait) 0 else offset.coerceAtMost(w - tileSize)
            val y = if (isPortrait) offset.coerceAtMost(h - tileSize) else 0

            val tile = Bitmap.createBitmap(bitmap, x, y, tileSize, tileSize)
            var result: DetectionResult? = null
            try {
                result = detect(tile)
            } catch (e: Exception) {
                Log.e(TAG, "Tiled scan: tile $i failed", e)
            }

            if (result != null) {
                allNms.addAll(result.detections)
                allDebug.addAll(result.allDetections)
                allClasses.addAll(result.detectedClasses)

                if (result.isUnsafe) {
                    Log.d(TAG, "Tiled scan: tile $i/$numTiles UNSAFE — early exit")
                    // Save crops from the tile BEFORE recycling — coords match tile dimensions
                    // saveUnsafeCrops(tile, result)
                    if (tile !== bitmap) tile.recycle()
                    return DetectionResult(true, allNms, allDebug, allClasses)
                }
            }
            if (tile !== bitmap) tile.recycle()
        }

        return DetectionResult(false, allNms, allDebug, allClasses)
    }

    /**
     * Save only the unsafe detection crops from the tile/bitmap that was fed to detect().
     * Each NMS-filtered detection is cropped, annotated with a label, and saved as a JPEG.
     *
     * Called internally by detectTiled() while the tile is still alive so coords are correct.
     * Saves to: /storage/emulated/0/Android/data/com.matrixlab.focustime/files/blocked/
     */
    private fun saveUnsafeCrops(tileBitmap: Bitmap, result: DetectionResult) {
        if (!result.isUnsafe || result.detections.isEmpty()) return
        try {
            val dir = File(context.getExternalFilesDir("blocked"), "")
            dir.mkdirs()

            val tileW = tileBitmap.width
            val tileH = tileBitmap.height

            // Read ALL tile pixels via getPixels — guaranteed to work
            // (same method used by letterboxBitmapToByteBuffer for inference)
            val allPixels = IntArray(tileW * tileH)
            tileBitmap.getPixels(allPixels, 0, tileW, 0, 0, tileW, tileH)

            // Letterbox params to map 320-scale boxes → tile pixel coords
            val srcW = tileW.toFloat()
            val srcH = tileH.toFloat()
            val scale = minOf(INPUT_SIZE / srcW, INPUT_SIZE / srcH)
            val padLeft = (INPUT_SIZE - srcW * scale) / 2f
            val padTop = (INPUT_SIZE - srcH * scale) / 2f

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

            val classColors = intArrayOf(
                Color.RED, Color.MAGENTA, Color.YELLOW, Color.CYAN, Color.GREEN
            )

            for ((idx, d) in result.detections.withIndex()) {
                // Map from model 320×320 letterbox coords to tile pixel coords
                val x1 = (((d.cx - d.w / 2f) - padLeft) / scale).toInt().coerceIn(0, tileW - 1)
                val y1 = (((d.cy - d.h / 2f) - padTop) / scale).toInt().coerceIn(0, tileH - 1)
                val x2 = (((d.cx + d.w / 2f) - padLeft) / scale).toInt().coerceIn(x1 + 1, tileW)
                val y2 = (((d.cy + d.h / 2f) - padTop) / scale).toInt().coerceIn(y1 + 1, tileH)

                val cropW = x2 - x1
                val cropH = y2 - y1
                if (cropW <= 0 || cropH <= 0) continue

                // Add padding around crop (20% each side) so context is visible
                val padX = (cropW * 0.2f).toInt()
                val padY = (cropH * 0.2f).toInt()
                val px1 = (x1 - padX).coerceAtLeast(0)
                val py1 = (y1 - padY).coerceAtLeast(0)
                val px2 = (x2 + padX).coerceAtMost(tileW)
                val py2 = (y2 + padY).coerceAtMost(tileH)

                val cw = px2 - px1
                val ch = py2 - py1
                if (cw <= 0 || ch <= 0) continue

                // Extract crop pixels manually from the allPixels array
                val cropPixels = IntArray(cw * ch)
                for (row in 0 until ch) {
                    System.arraycopy(
                        allPixels, (py1 + row) * tileW + px1,
                        cropPixels, row * cw,
                        cw
                    )
                }

                // Create a fresh mutable bitmap and set pixels directly
                val mutable = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
                mutable.setPixels(cropPixels, 0, cw, 0, 0, cw, ch)

                // Draw box + label on the crop
                val canvas = Canvas(mutable)
                val boxPaint = Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = classColors.getOrElse(d.classId) { Color.WHITE }
                }
                // Box coords relative to padded crop
                val bx1 = (x1 - px1).toFloat()
                val by1 = (y1 - py1).toFloat()
                val bx2 = (x2 - px1).toFloat()
                val by2 = (y2 - py1).toFloat()
                canvas.drawRect(bx1, by1, bx2, by2, boxPaint)

                val textPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 28f
                    isFakeBoldText = true
                    setShadowLayer(3f, 1f, 1f, Color.BLACK)
                }
                val label = "${classLabel(d.classId)} %.1f%%".format(d.confidence * 100)
                canvas.drawText(label, bx1 + 2, by1 - 4, textPaint)

                val file = File(dir, "UNSAFE_${timestamp}_${idx}_${classLabel(d.classId)}.jpg")
                FileOutputStream(file).use { out ->
                    mutable.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                mutable.recycle()
                Log.d(TAG, "Unsafe crop saved: ${file.absolutePath} (${cw}x${ch})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save unsafe crops", e)
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
        
        // Track how many times each class wins (best score), its max confidence, and total score sum
        val classBestCount = IntArray(NUM_CLASSES)
        val classBestMax = FloatArray(NUM_CLASSES)
        val classScoreSum = FloatArray(NUM_CLASSES)
        
        for (i in 0 until NUM_DETECTIONS) {
            // Find best class score + accumulate sums for all classes
            var maxScore = 0f
            var maxClass = 0
            for (c in 0 until NUM_CLASSES) {
                val score = output[BOX_COORDS + c][i]
                classScoreSum[c] += score
                if (score > maxScore) {
                    maxScore = score
                    maxClass = c
                }
            }
            
            classBestCount[maxClass]++
            classBestMax[maxClass] = maxOf(classBestMax[maxClass], maxScore)

            val det = Detection(
                cx = output[0][i],
                cy = output[1][i],
                w = output[2][i],
                h = output[3][i],
                classId = maxClass,
                confidence = maxScore
            )
            allCandidates.add(det)
            
            val threshold = CLASS_THRESHOLDS.getOrElse(maxClass) { 0.30f }
            val minArea = MIN_BOX_AREA_FRACTION * INPUT_SIZE * INPUT_SIZE
            if (maxScore >= threshold) {
                results.add(det)
            }
        }
        if (!results.isEmpty()) {
            // Single-line stats: class=count(max,sum)
            val stats = (0 until NUM_CLASSES).joinToString { c ->
                "${classLabel(c)}=${classBestCount[c]}(max=%.3f,sum=%.1f)".format(classBestMax[c], classScoreSum[c])
            }
            Log.d(TAG, "Stats: $stats | passed=${results.size}/$NUM_DETECTIONS")
        }
        
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

        // Log.d(TAG, "Letterbox: ${srcW}x${srcH} -> ${newW}x${newH}, pad=($padLeft,$padTop)")

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
