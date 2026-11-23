package com.likkho.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class ImageEditParams(
    val compress: Boolean = false,
    val format: String = "WebP",
    val quality: Int = 80,
    val maxDimension: Int? = null,
    val bitDepth: String = "24-bit RGB",
    val colorSpace: String = "sRGB",
    val stripExif: Boolean = true,
    val filterIndex: Int = 0,
    val brightness: Float = 100f,
    val contrast: Float = 100f,
    val saturation: Float = 100f,
    val warmth: Float = 0f,
    val hue: Float = 0f,
    val opacity: Float = 1f,
    val rotationDegrees: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val cropAspect: String = "Free",
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    fun normalizedRotation(): Int {
        val mod = rotationDegrees % 360
        return if (mod < 0) mod + 360 else mod
    }

    fun hasPixelOrEncodingChanges(): Boolean {
        return compress ||
            maxDimension != null ||
            bitDepth != "24-bit RGB" ||
            colorSpace != "sRGB" ||
            filterIndex != 0 ||
            abs(brightness - 100f) > 0.5f ||
            abs(contrast - 100f) > 0.5f ||
            abs(saturation - 100f) > 0.5f ||
            abs(warmth) > 0.5f ||
            abs(hue) > 0.5f ||
            normalizedRotation() != 0 ||
            flipH ||
            flipV ||
            cropAspect != "Free" ||
            zoom > 1.005f
    }

    fun hasGeometryChanges(): Boolean {
        return normalizedRotation() != 0 ||
            flipH ||
            flipV ||
            cropAspect != "Free" ||
            zoom > 1.005f ||
            maxDimension != null
    }

    fun withCompressionPreset(preset: CompressionPreset): ImageEditParams = when (preset) {
        CompressionPreset.BALANCED -> copy(
            compress = true,
            format = "WebP",
            quality = 82,
            maxDimension = 1600,
            bitDepth = "24-bit RGB",
            colorSpace = "sRGB",
            stripExif = true
        )
        CompressionPreset.COMPACT -> copy(
            compress = true,
            format = "WebP",
            quality = 65,
            maxDimension = 1280,
            bitDepth = "24-bit RGB",
            colorSpace = "sRGB",
            stripExif = true
        )
        CompressionPreset.TINY -> copy(
            compress = true,
            format = "JPEG",
            quality = 50,
            maxDimension = 800,
            bitDepth = "24-bit RGB",
            colorSpace = "sRGB",
            stripExif = true
        )
        CompressionPreset.LOSSLESS -> copy(
            compress = true,
            format = "PNG (Lossless)",
            quality = 100,
            maxDimension = null,
            bitDepth = "24-bit RGB",
            colorSpace = "sRGB",
            stripExif = true
        )
    }
}

enum class CompressionPreset(val label: String) {
    BALANCED("Balanced"),
    COMPACT("Compact"),
    TINY("Tiny"),
    LOSSLESS("Lossless")
}

data class CropBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

data class EncodedImageResult(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val params: ImageEditParams,
)

object ImageEditorEngine {

    val CROP_ASPECTS = listOf("Free", "1:1", "4:3", "3:4", "16:9", "9:16")
    val MAX_DIMENSIONS: List<Pair<String, Int?>> = listOf(
        "Orig" to null,
        "2048" to 2048,
        "1600" to 1600,
        "1280" to 1280,
        "800" to 800
    )

    fun parseAspectRatio(cropAspect: String): Float? = when (cropAspect) {
        "1:1" -> 1f
        "4:3" -> 4f / 3f
        "3:4" -> 3f / 4f
        "16:9" -> 16f / 9f
        "9:16" -> 9f / 16f
        else -> null
    }

    /**
     * Pure geometric calculation for crop rectangle given source dimensions, aspect preset,
     * zoom [1..4], and normalized pan [-1..1].
     */
    fun computeCropBounds(
        srcWidth: Int,
        srcHeight: Int,
        cropAspect: String,
        zoom: Float,
        panX: Float,
        panY: Float
    ): CropBounds {
        val safeW = srcWidth.coerceAtLeast(1)
        val safeH = srcHeight.coerceAtLeast(1)
        val safeZoom = zoom.coerceIn(1f, 4f)
        val targetAspect = parseAspectRatio(cropAspect)

        var baseW = safeW.toFloat()
        var baseH = safeH.toFloat()
        if (targetAspect != null) {
            val srcAspect = baseW / baseH
            if (srcAspect > targetAspect) {
                baseH = safeH.toFloat()
                baseW = baseH * targetAspect
            } else {
                baseW = safeW.toFloat()
                baseH = baseW / targetAspect
            }
        }

        val cropW = (baseW / safeZoom).roundToInt().coerceIn(1, safeW)
        val cropH = (baseH / safeZoom).roundToInt().coerceIn(1, safeH)
        val maxOffsetX = (safeW - cropW).coerceAtLeast(0)
        val maxOffsetY = (safeH - cropH).coerceAtLeast(0)

        val nx = panX.coerceIn(-1f, 1f)
        val ny = panY.coerceIn(-1f, 1f)
        val left = ((maxOffsetX * 0.5f) * (1f + nx)).roundToInt().coerceIn(0, maxOffsetX)
        val top = ((maxOffsetY * 0.5f) * (1f + ny)).roundToInt().coerceIn(0, maxOffsetY)
        return CropBounds(left = left, top = top, width = cropW, height = cropH)
    }

    fun computeScaledDimensions(width: Int, height: Int, maxDim: Int?): Pair<Int, Int> {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (maxDim == null || maxDim <= 0) return w to h
        if (w <= maxDim && h <= maxDim) return w to h
        val scale = min(maxDim.toFloat() / w, maxDim.toFloat() / h)
        return (w * scale).roundToInt().coerceAtLeast(1) to (h * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * Updates canvas image card dimensions proportionally after rotation/crop while preserving visual footprint.
     */
    fun computeUpdatedCanvasSize(
        currentWidthDp: Float,
        currentHeightDp: Float,
        newPixelWidth: Int,
        newPixelHeight: Int
    ): Pair<Float, Float> {
        val safePxW = newPixelWidth.coerceAtLeast(1)
        val safePxH = newPixelHeight.coerceAtLeast(1)
        val newAspect = safePxW.toFloat() / safePxH.toFloat()
        val curArea = (currentWidthDp.coerceAtLeast(48f) * currentHeightDp.coerceAtLeast(40f))
        var targetH = sqrt(curArea / newAspect).coerceIn(48f, 900f)
        var targetW = (targetH * newAspect).coerceIn(48f, 900f)
        if (targetW >= 900f) {
            targetW = 900f
            targetH = (targetW / newAspect).coerceIn(40f, 900f)
        }
        return targetW to targetH
    }

    fun computeSavingsPercent(originalBytes: Long, newBytes: Long): Int? {
        if (originalBytes <= 0L || newBytes <= 0L) return null
        return (((originalBytes - newBytes) * 100.0) / originalBytes).roundToInt()
    }

    /**
     * Renders a fast, responsive preview Bitmap without mutating or recycling [source].
     */
    fun processBitmapForPreview(
        source: Bitmap,
        params: ImageEditParams,
        maxPreviewDim: Int = 560
    ): Bitmap {
        val working = transformAndCrop(source, params, capMaxDim = maxPreviewDim)
        return applyLookAndBitDepth(working, params)
    }

    /**
     * Renders full edited Bitmap ready for encoding without mutating or recycling [source].
     */
    fun processBitmapForExport(source: Bitmap, params: ImageEditParams): Bitmap {
        val working = transformAndCrop(source, params, capMaxDim = params.maxDimension)
        return applyLookAndBitDepth(working, params)
    }

    fun encodeBitmap(bitmap: Bitmap, params: ImageEditParams): EncodedImageResult {
        val useLosslessPng = params.format.startsWith("PNG")
        val useJpeg = params.format == "JPEG"
        val q = params.quality.coerceIn(1, 100)

        // If PNG/16-bit/JPEG etc., prepare target Bitmap config cleanly
        val encodeSource = if (params.bitDepth == "16-bit Hi" && !useLosslessPng) {
            bitmap.copy(Bitmap.Config.RGB_565, false) ?: bitmap
        } else {
            bitmap
        }

        val baos = ByteArrayOutputStream()
        try {
            val fmt = when {
                useLosslessPng -> Bitmap.CompressFormat.PNG
                useJpeg -> Bitmap.CompressFormat.JPEG
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
                else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
            encodeSource.compress(fmt, if (useLosslessPng) 100 else q, baos)
            return EncodedImageResult(
                bytes = baos.toByteArray(),
                width = encodeSource.width,
                height = encodeSource.height,
                params = params
            )
        } finally {
            if (encodeSource !== bitmap && !encodeSource.isRecycled) {
                encodeSource.recycle()
            }
        }
    }

    private fun transformAndCrop(
        source: Bitmap,
        params: ImageEditParams,
        capMaxDim: Int?
    ): Bitmap {
        val rot = params.normalizedRotation()
        val hasMatrix = rot != 0 || params.flipH || params.flipV
        val oriented: Bitmap = if (hasMatrix) {
            val m = Matrix()
            if (rot != 0) m.postRotate(rot.toFloat())
            if (params.flipH || params.flipV) {
                m.postScale(if (params.flipH) -1f else 1f, if (params.flipV) -1f else 1f)
            }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val crop = computeCropBounds(
            srcWidth = oriented.width,
            srcHeight = oriented.height,
            cropAspect = params.cropAspect,
            zoom = params.zoom,
            panX = params.panX,
            panY = params.panY
        )

        val cropped: Bitmap = if (
            crop.left == 0 && crop.top == 0 &&
            crop.width == oriented.width && crop.height == oriented.height
        ) {
            oriented
        } else {
            val sub = Bitmap.createBitmap(oriented, crop.left, crop.top, crop.width, crop.height)
            if (oriented !== source) oriented.recycle()
            sub
        }

        val (targetW, targetH) = computeScaledDimensions(cropped.width, cropped.height, capMaxDim)
        return if (targetW == cropped.width && targetH == cropped.height) {
            cropped
        } else {
            val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
            if (scaled !== cropped && cropped !== source) cropped.recycle()
            scaled
        }
    }

    private fun applyLookAndBitDepth(workingInput: Bitmap, params: ImageEditParams): Bitmap {
        var current = workingInput
        if (current.config != Bitmap.Config.ARGB_8888 || !current.isMutable) {
            val mutableCopy = current.copy(Bitmap.Config.ARGB_8888, true)
            current.recycle()
            current = mutableCopy
        }

        // 1. Named Filter
        if (params.filterIndex != 0) {
            val filtered = applyNamedFilter(current, params.filterIndex)
            if (filtered !== current) {
                current.recycle()
                current = filtered
            }
        }

        // 2. ColorMatrix Adjustments + Color Space
        val cm = buildAdjustmentColorMatrix(params)
        if (cm != null) {
            val adjusted = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(adjusted)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
            canvas.drawBitmap(current, 0f, 0f, paint)
            current.recycle()
            current = adjusted
        }

        // 3. Bit Depth quantization
        val quantized = applyBitDepth(current, params.bitDepth)
        if (quantized !== current) {
            current.recycle()
            current = quantized
        }

        return current
    }

    private fun buildAdjustmentColorMatrix(params: ImageEditParams): ColorMatrix? {
        val hasBright = abs(params.brightness - 100f) > 0.5f
        val hasContrast = abs(params.contrast - 100f) > 0.5f
        val hasSat = abs(params.saturation - 100f) > 0.5f
        val hasWarm = abs(params.warmth) > 0.5f
        val hasHue = abs(params.hue) > 0.5f
        val forceGray = params.colorSpace == "Grayscale"
        val wideP3 = params.colorSpace == "Display P3"

        if (!hasBright && !hasContrast && !hasSat && !hasWarm && !hasHue && !forceGray && !wideP3) {
            return null
        }

        val combined = ColorMatrix()

        if (hasSat || forceGray || wideP3) {
            val satCm = ColorMatrix()
            val satVal = when {
                forceGray -> 0f
                wideP3 -> (params.saturation / 100f) * 1.12f
                else -> params.saturation / 100f
            }
            satCm.setSaturation(satVal.coerceIn(0f, 2.5f))
            combined.postConcat(satCm)
        }

        if (hasContrast || hasBright) {
            val c = (params.contrast / 100f).coerceIn(0.05f, 2.5f)
            val bOffset = ((params.brightness - 100f) / 100f) * 255f
            val translate = (-0.5f * c + 0.5f) * 255f + bOffset
            val bcCm = ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, translate,
                    0f, c, 0f, 0f, translate,
                    0f, 0f, c, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            combined.postConcat(bcCm)
        }

        if (hasWarm) {
            val wShift = (params.warmth / 100f).coerceIn(-1f, 1f) * 38f
            val warmCm = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, wShift,
                    0f, 1f, 0f, 0f, wShift * 0.25f,
                    0f, 0f, 1f, 0f, -wShift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            combined.postConcat(warmCm)
        }

        if (hasHue) {
            combined.postConcat(buildHueRotateMatrix(params.hue))
        }

        return combined
    }

    private fun buildHueRotateMatrix(degrees: Float): ColorMatrix {
        val rad = Math.toRadians(degrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val lumR = 0.213f
        val lumG = 0.715f
        val lumB = 0.072f
        val matrix = floatArrayOf(
            lumR + cosA * (1f - lumR) + sinA * (-lumR),
            lumG + cosA * (-lumG) + sinA * (-lumG),
            lumB + cosA * (-lumB) + sinA * (1f - lumB),
            0f, 0f,
            lumR + cosA * (-lumR) + sinA * 0.143f,
            lumG + cosA * (1f - lumG) + sinA * 0.140f,
            lumB + cosA * (-lumB) + sinA * (-0.283f),
            0f, 0f,
            lumR + cosA * (-lumR) + sinA * (-(1f - lumR)),
            lumG + cosA * (-lumG) + sinA * lumG,
            lumB + cosA * (1f - lumB) + sinA * lumB,
            0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        return ColorMatrix(matrix)
    }

    private fun applyColorMatrix(src: Bitmap, cm: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * Implements all 22 filters matching [IMAGE_FILTERS] indices:
     * 0 Original, 1 Grayscale, 2 Sepia, 3 Invert, 4 Blur, 5 Sharpen, 6 Edge Sobel,
     * 7 Threshold, 8 High-Pass, 9 Low-Pass, 10 Vignette, 11 Warmth/Coolness,
     * 12 Hue Rotate, 13 Vintage/Retro, 14 Noir, 15 HDR, 16 Color Balance,
     * 17 Posterize, 18 Emboss, 19 Pixelate, 20 Distortion, 21 Noise Reduction.
     */
    private fun applyNamedFilter(src: Bitmap, index: Int): Bitmap = when (index) {
        0 -> src
        1 -> { // Grayscale
            val cm = ColorMatrix().apply { setSaturation(0f) }
            applyColorMatrix(src, cm)
        }
        2 -> { // Sepia
            val cm = ColorMatrix(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            applyColorMatrix(src, cm)
        }
        3 -> { // Invert
            val cm = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            applyColorMatrix(src, cm)
        }
        4 -> boxBlur(src, radius = max(2, min(src.width, src.height) / 140))
        5 -> convolve3x3(src, intArrayOf(0, -1, 0, -1, 5, -1, 0, -1, 0), divisor = 1, bias = 0)
        6 -> sobelEdgeDetect(src)
        7 -> thresholdBinarize(src, cutoff = 128)
        8 -> highPassFilter(src)
        9 -> convolve3x3(src, intArrayOf(1, 2, 1, 2, 4, 2, 1, 2, 1), divisor = 16, bias = 0) // Low-Pass Gaussian
        10 -> applyVignette(src, strength = 0.72f)
        11 -> { // Warmth / Coolness preset: rich golden amber boost
            val cm = ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0f, 0f, 14f,
                    0f, 1.02f, 0f, 0f, 4f,
                    0f, 0f, 0.90f, 0f, -12f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            applyColorMatrix(src, cm)
        }
        12 -> applyColorMatrix(src, buildHueRotateMatrix(90f))
        13 -> { // Vintage / Retro
            val cm = ColorMatrix(
                floatArrayOf(
                    0.92f, 0.18f, 0.04f, 0f, 16f,
                    0.06f, 0.86f, 0.06f, 0f, 12f,
                    0.02f, 0.14f, 0.72f, 0f, 8f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val tinted = applyColorMatrix(src, cm)
            val vig = applyVignette(tinted, strength = 0.55f)
            if (vig !== tinted) tinted.recycle()
            vig
        }
        14 -> { // Noir
            val cm = ColorMatrix(
                floatArrayOf(
                    0.34f, 0.68f, 0.16f, 0f, -24f,
                    0.34f, 0.68f, 0.16f, 0f, -24f,
                    0.34f, 0.68f, 0.16f, 0f, -24f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val bw = applyColorMatrix(src, cm)
            val vig = applyVignette(bw, strength = 0.78f)
            if (vig !== bw) bw.recycle()
            vig
        }
        15 -> applyHdrBoost(src)
        16 -> { // Color Balance: cool shadows / warm highlights
            val cm = ColorMatrix(
                floatArrayOf(
                    1.06f, 0.02f, -0.04f, 0f, 6f,
                    0f, 1.04f, 0f, 0f, 2f,
                    -0.04f, 0.02f, 1.08f, 0f, 8f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            applyColorMatrix(src, cm)
        }
        17 -> posterize(src, levels = 5)
        18 -> convolve3x3(src, intArrayOf(-2, -1, 0, -1, 1, 1, 0, 1, 2), divisor = 1, bias = 96) // Emboss
        19 -> pixelate(src, blockRatio = 36)
        20 -> barrelDistortion(src, strength = 0.45f)
        21 -> boxBlur(src, radius = 1) // Light Noise Reduction smoothing
        else -> src
    }

    private fun applyVignette(src: Bitmap, strength: Float): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.5f
        val radius = max(w, h) * 0.72f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx,
                cy,
                radius.coerceAtLeast(1f),
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb((strength * 80).roundToInt().coerceIn(0, 255), 0, 0, 0),
                    Color.argb((strength * 225).roundToInt().coerceIn(0, 255), 0, 0, 0)
                ),
                floatArrayOf(0.45f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, paint)
        return out
    }

    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 10)
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        val div = r * 2 + 1

        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            var rSum = 0; var gSum = 0; var bSum = 0; var aSum = 0
            for (k in -r..r) {
                val p = pix[row + k.coerceIn(0, w - 1)]
                aSum += (p ushr 24) and 0xFF
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = ((aSum / div) shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val addIdx = (x + r + 1).coerceIn(0, w - 1)
                val remIdx = (x - r).coerceIn(0, w - 1)
                val pAdd = pix[row + addIdx]
                val pRem = pix[row + remIdx]
                aSum += ((pAdd ushr 24) and 0xFF) - ((pRem ushr 24) and 0xFF)
                rSum += ((pAdd ushr 16) and 0xFF) - ((pRem ushr 16) and 0xFF)
                gSum += ((pAdd ushr 8) and 0xFF) - ((pRem ushr 8) and 0xFF)
                bSum += (pAdd and 0xFF) - (pRem and 0xFF)
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            var rSum = 0; var gSum = 0; var bSum = 0; var aSum = 0
            for (k in -r..r) {
                val p = tmp[k.coerceIn(0, h - 1) * w + x]
                aSum += (p ushr 24) and 0xFF
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                out[y * w + x] = ((aSum / div) shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val addY = (y + r + 1).coerceIn(0, h - 1)
                val remY = (y - r).coerceIn(0, h - 1)
                val pAdd = tmp[addY * w + x]
                val pRem = tmp[remY * w + x]
                aSum += ((pAdd ushr 24) and 0xFF) - ((pRem ushr 24) and 0xFF)
                rSum += ((pAdd ushr 16) and 0xFF) - ((pRem ushr 16) and 0xFF)
                gSum += ((pAdd ushr 8) and 0xFF) - ((pRem ushr 8) and 0xFF)
                bSum += (pAdd and 0xFF) - (pRem and 0xFF)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun convolve3x3(src: Bitmap, kernel: IntArray, divisor: Int, bias: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val div = if (divisor == 0) 1 else divisor
        for (y in 0 until h) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y2 = (y + 1).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x2 = (x + 1).coerceAtMost(w - 1)
                val center = pix[y * w + x]
                val a = (center ushr 24) and 0xFF
                var rAcc = 0; var gAcc = 0; var bAcc = 0
                val xs = intArrayOf(x0, x, x2)
                val ys = intArrayOf(y0, y, y2)
                var ki = 0
                for (ky in 0..2) {
                    val rowOff = ys[ky] * w
                    for (kx in 0..2) {
                        val wt = kernel[ki++]
                        if (wt != 0) {
                            val p = pix[rowOff + xs[kx]]
                            rAcc += ((p ushr 16) and 0xFF) * wt
                            gAcc += ((p ushr 8) and 0xFF) * wt
                            bAcc += (p and 0xFF) * wt
                        }
                    }
                }
                val r = (rAcc / div + bias).coerceIn(0, 255)
                val g = (gAcc / div + bias).coerceIn(0, 255)
                val b = (bAcc / div + bias).coerceIn(0, 255)
                out[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun sobelEdgeDetect(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        for (i in pix.indices) {
            val p = pix[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = (r * 77 + g * 150 + b * 29) shr 8
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y2 = (y + 1).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x2 = (x + 1).coerceAtMost(w - 1)
                val p00 = lum[y0 * w + x0]; val p01 = lum[y0 * w + x]; val p02 = lum[y0 * w + x2]
                val p10 = lum[y * w + x0]; val p12 = lum[y * w + x2]
                val p20 = lum[y2 * w + x0]; val p21 = lum[y2 * w + x]; val p22 = lum[y2 * w + x2]
                val gx = -p00 + p02 - 2 * p10 + 2 * p12 - p20 + p22
                val gy = -p00 - 2 * p01 - p02 + p20 + 2 * p21 + p22
                val mag = (abs(gx) + abs(gy)).coerceIn(0, 255)
                val a = (pix[y * w + x] ushr 24) and 0xFF
                out[y * w + x] = (a shl 24) or (mag shl 16) or (mag shl 8) or mag
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun thresholdBinarize(src: Bitmap, cutoff: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        for (i in pix.indices) {
            val p = pix[i]
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) shr 8
            val v = if (lum >= cutoff) 255 else 0
            pix[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun highPassFilter(src: Bitmap): Bitmap {
        val blurred = boxBlur(src, radius = 3)
        return try {
            val w = src.width
            val h = src.height
            val orig = IntArray(w * h)
            val low = IntArray(w * h)
            src.getPixels(orig, 0, w, 0, 0, w, h)
            blurred.getPixels(low, 0, w, 0, 0, w, h)
            for (i in orig.indices) {
                val p1 = orig[i]; val p2 = low[i]
                val a = (p1 ushr 24) and 0xFF
                val r = (((p1 ushr 16) and 0xFF) - ((p2 ushr 16) and 0xFF) + 128).coerceIn(0, 255)
                val g = (((p1 ushr 8) and 0xFF) - ((p2 ushr 8) and 0xFF) + 128).coerceIn(0, 255)
                val b = ((p1 and 0xFF) - (p2 and 0xFF) + 128).coerceIn(0, 255)
                orig[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            Bitmap.createBitmap(orig, w, h, Bitmap.Config.ARGB_8888)
        } finally {
            blurred.recycle()
        }
    }

    private fun applyHdrBoost(src: Bitmap): Bitmap {
        val blurred = boxBlur(src, radius = 2)
        return try {
            val w = src.width
            val h = src.height
            val orig = IntArray(w * h)
            val low = IntArray(w * h)
            src.getPixels(orig, 0, w, 0, 0, w, h)
            blurred.getPixels(low, 0, w, 0, 0, w, h)
            for (i in orig.indices) {
                val p1 = orig[i]; val p2 = low[i]
                val a = (p1 ushr 24) and 0xFF
                val r1 = (p1 ushr 16) and 0xFF; val r2 = (p2 ushr 16) and 0xFF
                val g1 = (p1 ushr 8) and 0xFF; val g2 = (p2 ushr 8) and 0xFF
                val b1 = p1 and 0xFF; val b2 = p2 and 0xFF
                // Unsharp micro-contrast + shadow lift + vibrancy
                val r = (r1 + ((r1 - r2) * 0.75f)).roundToInt().coerceIn(0, 255)
                val g = (g1 + ((g1 - g2) * 0.75f)).roundToInt().coerceIn(0, 255)
                val b = (b1 + ((b1 - b2) * 0.75f)).roundToInt().coerceIn(0, 255)
                val lum = (r + g + b) / 3f
                val rr = (lum + (r - lum) * 1.25f).roundToInt().coerceIn(0, 255)
                val gg = (lum + (g - lum) * 1.25f).roundToInt().coerceIn(0, 255)
                val bb = (lum + (b - lum) * 1.25f).roundToInt().coerceIn(0, 255)
                orig[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            Bitmap.createBitmap(orig, w, h, Bitmap.Config.ARGB_8888)
        } finally {
            blurred.recycle()
        }
    }

    private fun posterize(src: Bitmap, levels: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val step = 255f / (levels - 1).coerceAtLeast(1)
        for (i in pix.indices) {
            val p = pix[i]
            val a = (p ushr 24) and 0xFF
            val r = (((((p ushr 16) and 0xFF) / step).roundToInt()) * step).roundToInt().coerceIn(0, 255)
            val g = (((((p ushr 8) and 0xFF) / step).roundToInt()) * step).roundToInt().coerceIn(0, 255)
            val b = ((((p and 0xFF) / step).roundToInt()) * step).roundToInt().coerceIn(0, 255)
            pix[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pixelate(src: Bitmap, blockRatio: Int): Bitmap {
        val tinyW = (src.width / blockRatio).coerceAtLeast(12)
        val tinyH = (src.height / blockRatio).coerceAtLeast(12)
        val tiny = Bitmap.createScaledBitmap(src, tinyW, tinyH, true)
        return try {
            Bitmap.createScaledBitmap(tiny, src.width, src.height, false)
        } finally {
            if (tiny !== src) tiny.recycle()
        }
    }

    private fun barrelDistortion(src: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val cx = w * 0.5f
        val cy = h * 0.5f
        val invRx = 1f / cx.coerceAtLeast(1f)
        val invRy = 1f / cy.coerceAtLeast(1f)
        for (y in 0 until h) {
            val ny = (y - cy) * invRy
            val ny2 = ny * ny
            for (x in 0 until w) {
                val nx = (x - cx) * invRx
                val r2 = nx * nx + ny2
                val factor = 1f / (1f + strength * r2)
                val sx = (cx + nx * factor * cx).roundToInt().coerceIn(0, w - 1)
                val sy = (cy + ny * factor * cy).roundToInt().coerceIn(0, h - 1)
                out[y * w + x] = pix[sy * w + sx]
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun applyBitDepth(src: Bitmap, bitDepth: String): Bitmap = when (bitDepth) {
        "8-bit 256c" -> {
            val w = src.width; val h = src.height
            val pix = IntArray(w * h)
            src.getPixels(pix, 0, w, 0, 0, w, h)
            for (i in pix.indices) {
                val p = pix[i]
                val a = (p ushr 24) and 0xFF
                val r = (((p ushr 16) and 0xFF) / 36) * 36
                val g = (((p ushr 8) and 0xFF) / 36) * 36
                val b = ((p and 0xFF) / 85) * 85
                pix[i] = (a shl 24) or (r.coerceAtMost(255) shl 16) or (g.coerceAtMost(255) shl 8) or b.coerceAtMost(255)
            }
            Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
        }
        "1-bit Mono" -> thresholdBinarize(src, cutoff = 128)
        else -> src
    }
}
