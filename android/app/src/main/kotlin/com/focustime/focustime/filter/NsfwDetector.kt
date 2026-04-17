package com.matrixlab.focustime.filter

import android.content.Context
import android.content.SharedPreferences
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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8-based NSFW object detector using NudeNet 18-class model.
 *
 * Input:  [1, 3, 640, 640] float32, pixels normalised to [0,1], RGB
 * Output: [1, 22, 8400]    float32
 *         rows 0-3 = cx, cy, w, h  (in pixels, 640-scale)
 *         rows 4-21 = class scores for 18 NudeNet classes
 *
 * The screen is dynamically divided into square tiles to avoid distortion.
 * Each tile is resized to 640x640 (square->square = no distortion).
 */
class NsfwDetector(private val context: Context) {

    companion object {
        private const val TAG = "NsfwDetector"
        private const val MODEL_FILE = "temp_model.tflite"
        private const val INPUT_SIZE = 640
        private const val NUM_DETECTIONS = 8400
        private const val NUM_CLASSES = 18
        private const val BOX_COORDS = 4
        private const val CONF_THRESHOLD = 0.2f
        private const val IOU_THRESHOLD = 0.45f
        // Minimum box area as fraction of model input area (640x640).
        private const val MIN_BOX_AREA_FRACTION = 0.005f // 0.5% of 640x640 = ~2048 px^2
        // Cap tile size so downscale to 640 is at most ~2x
        private const val MAX_TILE_PX = INPUT_SIZE * 2 // 1280
        // Overlap ratio between tiles (15%)
        private const val TILE_OVERLAP_RATIO = 0.15f

        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val KEY_AI_BLOCKING_LEVEL = "flutter.ai_blocking_level"

        // 18 NudeNet classes in model index order (must match Python CLASSES list exactly)
        private val CLASS_NAMES = arrayOf(
            "FEMALE_GENITALIA_COVERED",  // 0
            "FACE_FEMALE",               // 1
            "BUTTOCKS_EXPOSED",          // 2
            "FEMALE_BREAST_EXPOSED",     // 3
            "FEMALE_GENITALIA_EXPOSED",  // 4
            "MALE_BREAST_EXPOSED",       // 5
            "ANUS_EXPOSED",              // 6
            "FEET_EXPOSED",              // 7
            "BELLY_COVERED",             // 8
            "FEET_COVERED",              // 9
            "ARMPITS_COVERED",           // 10
            "ARMPITS_EXPOSED",           // 11
            "FACE_MALE",                 // 12
            "BELLY_EXPOSED",             // 13
            "MALE_GENITALIA_EXPOSED",    // 14
            "ANUS_COVERED",              // 15
            "FEMALE_BREAST_COVERED",     // 16
            "BUTTOCKS_COVERED"           // 17
        )

        // Blocking level -> set of class indices that trigger blocking
        private val LEVEL_PORN = setOf(2, 3, 4, 6, 13, 14)
        private val LEVEL_NUDE = LEVEL_PORN + setOf(0, 8, 11, 15, 16, 17)
        private val LEVEL_FEMALE = LEVEL_NUDE + setOf(1)

        fun blockedClassesForLevel(level: String): Set<Int> = when (level) {
            "nude" -> LEVEL_NUDE
            "female" -> LEVEL_FEMALE
            else -> LEVEL_PORN // default
        }
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Pre-allocated reusable buffers for performance (avoid GC pressure)
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).apply {
        order(ByteOrder.nativeOrder())
    }
    private val rawOutput: Array<Array<FloatArray>> = Array(1) { Array(BOX_COORDS + NUM_CLASSES) { FloatArray(NUM_DETECTIONS) } }

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
            Log.d(TAG, "NSFW YOLO detector loaded (input=${INPUT_SIZE}x${INPUT_SIZE}, classes=$NUM_CLASSES, detections=$NUM_DETECTIONS)")
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

    /** Read current blocking level from SharedPreferences. */
    private fun getBlockedClasses(): Set<Int> {
        val level = try {
            prefs.getString(KEY_AI_BLOCKING_LEVEL, "porn") ?: "porn"
        } catch (e: Exception) {
            "porn"
        }
        return blockedClassesForLevel(level)
    }

    data class DetectionResult(
        val isUnsafe: Boolean,
        val detections: List<Detection>,
        val allDetections: List<Detection>,
        val detectedClasses: Set<Int>
    )

    /**
     * Run detection on a single (square) tile bitmap.
     * Resizes to 640x640 (square->square = zero distortion), runs inference,
     * applies NMS, and checks against the active blocking level.
     */
    fun detect(bitmap: Bitmap): DetectionResult {
        val interp = interpreter ?: return DetectionResult(false, emptyList(), emptyList(), emptySet())
        return try {
            // 1. Resize square tile to 640x640 and fill input buffer
            fillInputBuffer(bitmap)

            // 2. Run inference — output shape [1, 22, 8400]
            for (row in rawOutput[0]) row.fill(0f)
            interp.run(inputBuffer, rawOutput)

            // 3. Parse [22, 8400] -> filtered detections + NMS
            val (filtered, topAll) = parseDetections(rawOutput[0])
            val nmsResults = nonMaxSuppression(filtered)

            // 4. Check against active blocking level
            val detectedClasses = nmsResults.map { it.classId }.toSet()
            val blockedClasses = getBlockedClasses()
            val isUnsafe = nmsResults.any { it.classId in blockedClasses }

            DetectionResult(isUnsafe, nmsResults, topAll, detectedClasses)
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            DetectionResult(false, emptyList(), emptyList(), emptySet())
        }
    }

    // -- Dynamic square tiling ------------------------------------------------

    /**
     * Compute a grid of square tile regions that cover the entire image.
     *
     * Algorithm (following Python tiling.py logic):
     * 1. tileSize = min(w, h), capped at MAX_TILE_PX (1280) to avoid >2x downscale
     * 2. Compute cols/rows needed with TILE_OVERLAP_RATIO overlap
     * 3. Evenly space tiles; last tile anchored to edge for full coverage
     *
     * Returns list of [x, y, size, size] regions.
     */
    private fun computeSquareTiles(imgW: Int, imgH: Int): List<IntArray> {
        var tileSize = min(imgW, imgH)
        if (tileSize > MAX_TILE_PX) tileSize = MAX_TILE_PX

        val cols = if (imgW <= tileSize) 1
                   else max(1, ceil(imgW.toDouble() / (tileSize * (1.0 - TILE_OVERLAP_RATIO))).toInt())
        val rows = if (imgH <= tileSize) 1
                   else max(1, ceil(imgH.toDouble() / (tileSize * (1.0 - TILE_OVERLAP_RATIO))).toInt())

        val tiles = mutableListOf<IntArray>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = when {
                    cols == 1 -> 0
                    c == cols - 1 -> imgW - tileSize  // anchor last col to right edge
                    else -> (c * (imgW - tileSize).toDouble() / (cols - 1)).toInt()
                }
                val y = when {
                    rows == 1 -> 0
                    r == rows - 1 -> imgH - tileSize  // anchor last row to bottom edge
                    else -> (r * (imgH - tileSize).toDouble() / (rows - 1)).toInt()
                }
                tiles.add(intArrayOf(
                    max(0, x),
                    max(0, y),
                    min(tileSize, imgW - max(0, x)),
                    min(tileSize, imgH - max(0, y))
                ))
            }
        }
        return tiles
    }

    /**
     * Tiled detection: dynamically divides the bitmap into square tiles,
     * runs inference on each, and early-exits on the first unsafe tile.
     *
     * Tiles are always square so resizing to 640x640 introduces zero distortion.
     * Tile size and count adapt to the actual screen dimensions.
     */
    fun detectTiled(bitmap: Bitmap): DetectionResult {
        interpreter ?: return DetectionResult(false, emptyList(), emptyList(), emptySet())

        val w = bitmap.width
        val h = bitmap.height

        // Nearly square — single tile, no splitting needed
        if (w > 0 && h > 0 && max(w, h).toFloat() / min(w, h) <= 1.15f) {
            return detect(bitmap)
        }

        val tiles = computeSquareTiles(w, h)
        Log.d(TAG, "Tiled scan: ${w}x${h} -> ${tiles.size} tiles")

        val allNms = mutableListOf<Detection>()
        val allDebug = mutableListOf<Detection>()
        val allClasses = mutableSetOf<Int>()

        for ((idx, tile) in tiles.withIndex()) {
            val tx = tile[0]; val ty = tile[1]; val tw = tile[2]; val th = tile[3]
            // Ensure square crop (use min side if edge tile is slightly non-square)
            val cropSize = min(tw, th)
            val tileBmp = Bitmap.createBitmap(bitmap, tx, ty, cropSize, cropSize)
            var result: DetectionResult? = null
            try {
                result = detect(tileBmp)
            } catch (e: Exception) {
                Log.e(TAG, "Tiled scan: tile $idx failed", e)
            }

            if (result != null) {
                allNms.addAll(result.detections)
                allDebug.addAll(result.allDetections)
                allClasses.addAll(result.detectedClasses)

                if (result.isUnsafe) {
                    Log.d(TAG, "Tiled scan: tile $idx/${tiles.size} UNSAFE — early exit")
                    if (tileBmp !== bitmap) tileBmp.recycle()
                    return DetectionResult(true, allNms, allDebug, allClasses)
                }
            }
            if (tileBmp !== bitmap) tileBmp.recycle()
        }

        return DetectionResult(false, allNms, allDebug, allClasses)
    }

    // -- Preprocessing --------------------------------------------------------

    /**
     * Resize bitmap to INPUT_SIZE x INPUT_SIZE and fill the reusable inputBuffer.
     * Since tiles are always square, this is a simple scale with no distortion.
     * Normalizes pixels to [0,1] float32 in RGB channel order.
     *
     * Python reference: cv2.dnn.blobFromImage(img, 1/255.0, (640,640), swapRB=True)
     */
    private fun fillInputBuffer(bitmap: Bitmap) {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (resized !== bitmap) resized.recycle()

        inputBuffer.rewind()
        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)          // B
        }
    }

    // -- Post-processing ------------------------------------------------------

    data class Detection(
        val cx: Float, val cy: Float, val w: Float, val h: Float,
        val classId: Int, val confidence: Float
    )

    /**
     * Parse [22, 8400] output -> list of detections, filtering by CONF_THRESHOLD.
     * Returns Pair(filtered detections, top-20 raw detections for debug).
     */
    private fun parseDetections(output: Array<FloatArray>): Pair<List<Detection>, List<Detection>> {
        val results = mutableListOf<Detection>()
        val allCandidates = mutableListOf<Detection>()

        val classBestCount = IntArray(NUM_CLASSES)
        val classBestMax = FloatArray(NUM_CLASSES)

        val minArea = MIN_BOX_AREA_FRACTION * INPUT_SIZE * INPUT_SIZE

        for (i in 0 until NUM_DETECTIONS) {
            var maxScore = 0f
            var maxClass = 0
            for (c in 0 until NUM_CLASSES) {
                val score = output[BOX_COORDS + c][i]
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

            // Filter by confidence and minimum box area
            if (maxScore >= CONF_THRESHOLD && det.w * det.h >= minArea) {
                results.add(det)
            }
        }

        if (results.isNotEmpty()) {
            val stats = (0 until NUM_CLASSES)
                .filter { classBestMax[it] > 0.01f }
                .joinToString(" | ") { c ->
                    "${CLASS_NAMES[c]}=${classBestCount[c]}(max=%.3f)".format(classBestMax[c])
                }
            Log.d(TAG, "Stats: $stats | passed=${results.size}/$NUM_DETECTIONS")
        }

        val top20 = allCandidates.sortedByDescending { it.confidence }.take(20)
        return Pair(results, top20)
    }

    /** Simple greedy NMS by class. */
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

    // -- Debug: save unsafe crops ---------------------------------------------

    /**
     * Save detected NSFW crops as JPEG files for debugging.
     * Saves to: /storage/emulated/0/Android/data/com.matrixlab.focustime/files/blocked/
     */
    @Suppress("unused")
    private fun saveUnsafeCrops(tileBitmap: Bitmap, result: DetectionResult) {
        if (!result.isUnsafe || result.detections.isEmpty()) return
        try {
            val dir = File(context.getExternalFilesDir("blocked"), "")
            dir.mkdirs()

            val tileW = tileBitmap.width
            val tileH = tileBitmap.height

            val allPixels = IntArray(tileW * tileH)
            tileBitmap.getPixels(allPixels, 0, tileW, 0, 0, tileW, tileH)

            // Since tiles are square and resized directly (no letterbox), mapping is:
            // model_coord * (tileSize / INPUT_SIZE) = pixel_coord
            val scaleX = tileW.toFloat() / INPUT_SIZE
            val scaleY = tileH.toFloat() / INPUT_SIZE

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

            val classColors = intArrayOf(
                Color.RED, Color.MAGENTA, Color.YELLOW, Color.CYAN, Color.GREEN,
                Color.BLUE, Color.LTGRAY, Color.DKGRAY, Color.WHITE,
                0xFFFF6600.toInt(), 0xFF00FF66.toInt(), 0xFF6600FF.toInt(),
                0xFFFF0066.toInt(), 0xFF0066FF.toInt(), 0xFF66FF00.toInt(),
                0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFF00.toInt()
            )

            for ((idx, d) in result.detections.withIndex()) {
                val x1 = ((d.cx - d.w / 2f) * scaleX).toInt().coerceIn(0, tileW - 1)
                val y1 = ((d.cy - d.h / 2f) * scaleY).toInt().coerceIn(0, tileH - 1)
                val x2 = ((d.cx + d.w / 2f) * scaleX).toInt().coerceIn(x1 + 1, tileW)
                val y2 = ((d.cy + d.h / 2f) * scaleY).toInt().coerceIn(y1 + 1, tileH)

                val cropW = x2 - x1
                val cropH = y2 - y1
                if (cropW <= 0 || cropH <= 0) continue

                val padX = (cropW * 0.2f).toInt()
                val padY = (cropH * 0.2f).toInt()
                val px1 = (x1 - padX).coerceAtLeast(0)
                val py1 = (y1 - padY).coerceAtLeast(0)
                val px2 = (x2 + padX).coerceAtMost(tileW)
                val py2 = (y2 + padY).coerceAtMost(tileH)

                val cw = px2 - px1
                val ch = py2 - py1
                if (cw <= 0 || ch <= 0) continue

                val cropPixels = IntArray(cw * ch)
                for (row in 0 until ch) {
                    System.arraycopy(
                        allPixels, (py1 + row) * tileW + px1,
                        cropPixels, row * cw,
                        cw
                    )
                }

                val mutable = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
                mutable.setPixels(cropPixels, 0, cw, 0, 0, cw, ch)

                val canvas = Canvas(mutable)
                val boxPaint = Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = classColors.getOrElse(d.classId) { Color.WHITE }
                }
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

    private fun classLabel(id: Int): String =
        CLASS_NAMES.getOrElse(id) { "unknown" }

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
