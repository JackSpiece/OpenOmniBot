package cn.com.omnimind.baselib.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.util.Base64
import androidx.annotation.RequiresApi
import cn.com.omnimind.baselib.Constants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageUtils {
    /**
     * 从 Base64 字符串解码为 Bitmap
     * @param imageBase64 Base64 编码的图片字符串
     * @return 解码后的 Bitmap
     * @throws IllegalStateException 如果解码失败
     */
    fun decodeBase64ToBitmap(imageBase64: String): Bitmap {
        val cleanBase64 = imageBase64.replace("data:image/jpeg;base64,", "")
        val byteArray = Base64.decode(cleanBase64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
            ?: throw IllegalStateException("Failed to decode bitmap from base64 string")
    }

    /**
     * 判断bitmap是否大部分是单一颜色（纯色检测）
     * @param bitmap 要检查的位图
     * @param threshold 阈值百分比（0.0-1.0），默认0.70，表示70%的像素是同一颜色
     * @param sampleRate 采样率，默认10，每10个像素采样一次
     * @return 返回true表示大部分像素是单一颜色
     */
    fun isMostlySingleColor(
        bitmap: Bitmap, threshold: Float = 0.70f, sampleRate: Int = 10
    ): Boolean {
        val totalPixels = (bitmap.width / sampleRate) * (bitmap.height / sampleRate)
        if (totalPixels <= 0) return false

        val colorCount = mutableMapOf<Int, Int>()

        // 采样统计颜色分布
        for (x in 0 until bitmap.width step sampleRate) {
            for (y in 0 until bitmap.height step sampleRate) {
                val pixel = bitmap.getPixel(x, y)
                colorCount[pixel] = colorCount.getOrDefault(pixel, 0) + 1
            }
        }

        // 找到出现频率最高的颜色及其出现次数
        val maxCount = colorCount.values.maxOrNull() ?: 0
        // 计算占比，如果最高频率的颜色占比超过阈值，则认为是纯色
        val ratio = maxCount.toFloat() / totalPixels
        return ratio >= threshold
    }

    /**
     * 判断bitmap是否大部分是#A0A0A0或以上色值，且RGB色值相差不超过10
     * @param bitmap 要检查的位图
     * @param thresholdPercent 阈值百分比（0.0-1.0），默认0.90，表示90%的像素满足条件即可
     * @return 返回true表示大部分像素是#A0A0A0或以上色值且RGB色值相近
     */
    fun isMostlyLightBackground(
        bitmap: Bitmap,
        thresholdPercent: Float = 0.90f
    ): Boolean {
        if (bitmap.isRecycled) {
            return false
        }

        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return false
        }

        // #A0A0A0 的RGB值 (160, 160, 160)
        val thresholdRgb = 160

        // 排除顶部5%和底部5%的区域，只检测中间90%的区域
        val topBoundary = (height * 0.05f).toInt()
        val bottomBoundary = (height * 0.95f).toInt()

        // 为了提高性能，使用采样检查（每3个像素采样一次，平衡性能和准确性）
        val sampleRate = 3

        var validPixelCount = 0
        var totalPixelCount = 0

        // 检测中间90%的区域
        for (y in topBoundary until bottomBoundary step sampleRate) {
            for (x in 0 until width step sampleRate) {
                totalPixelCount++
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // 判断RGB值是否都>=160（#A0A0A0或以上）
                val isAboveA0A0A0 = r >= thresholdRgb && g >= thresholdRgb && b >= thresholdRgb

                // RGB色值相差不超过10（判断是否为接近灰色/浅色，避免彩色区域）
                val maxRgb = maxOf(r, g, b)
                val minRgb = minOf(r, g, b)
                val rgbDifference = maxRgb - minRgb
                val isLowRgbDifference = rgbDifference <= 10

                // 同时满足#A0A0A0或以上和RGB色值相差不超过10
                if (isAboveA0A0A0 && isLowRgbDifference) {
                    validPixelCount++
                }
            }
        }

        // 如果满足条件的像素占比超过阈值，返回true
        if (totalPixelCount > 0) {
            val validRatio = validPixelCount.toFloat() / totalPixelCount
            return validRatio >= thresholdPercent
        }

        return false
    }

    fun isMostlyBlackScreen(bitmap: Bitmap, threshold: Float = 0.80f): Boolean {
        var blackPixelCount = 0
        val totalPixels = bitmap.width * bitmap.height

        for (x in 0 until bitmap.width step 10) { // 采样检测，提高性能
            for (y in 0 until bitmap.height step 10) {
                val pixel = bitmap.getPixel(x, y)
                if (pixel == Color.BLACK) {
                    blackPixelCount++
                }
            }
        }

        return (blackPixelCount.toFloat() * 100 / totalPixels) > threshold
    }

    /**
     * 检测左侧或右侧1/5区域是否大面积纯色
     * 用于检测页面切换中的截屏图片（通常页面切换时会出现纯色区域）
     * @param bitmap 要检查的位图
     * @param threshold 阈值百分比（0.0-1.0），默认0.70，表示70%的像素是同一颜色
     * @param sampleRate 采样率，默认10，每10个像素采样一次
     * @param checkLeft 是否检测左侧区域，true检测左侧，false检测右侧
     * @return 返回true表示检测到纯色区域（可能是页面切换中）
     */
    fun isSideRegionMostlySingleColor(
        bitmap: Bitmap,
        threshold: Float = 0.95f,
        sampleRate: Int = 10,
        checkLeft: Boolean = true
    ): Boolean {
        if (bitmap.isRecycled) {
            return false
        }

        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return false
        }

        // 计算1/5区域的宽度
        val regionWidth = width / 5
        if (regionWidth <= 0) {
            return false
        }

        // 确定检测区域的范围
        val startX = if (checkLeft) 0 else (width - regionWidth)
        val endX = if (checkLeft) regionWidth else width

        // 计算采样后的总像素数
        val sampledWidth = (endX - startX) / sampleRate
        val sampledHeight = height / sampleRate
        val totalPixels = sampledWidth * sampledHeight
        if (totalPixels <= 0) {
            return false
        }

        val colorCount = mutableMapOf<Int, Int>()

        // 采样统计颜色分布（只检测左侧或右侧1/5区域）
        for (x in startX until endX step sampleRate) {
            for (y in 0 until height step sampleRate) {
                val pixel = bitmap.getPixel(x, y)
                colorCount[pixel] = colorCount.getOrDefault(pixel, 0) + 1
            }
        }

        // 找到出现频率最高的颜色及其出现次数
        val maxCount = colorCount.values.maxOrNull() ?: 0
        // 计算占比，如果最高频率的颜色占比超过阈值，则认为是纯色
        val ratio = maxCount.toFloat() / totalPixels
        return ratio >= threshold
    }

    /**
     * 检测左侧或右侧1/5区域是否大面积纯色（同时检测两侧）
     * 用于检测页面切换中的截屏图片
     * @param bitmap 要检查的位图
     * @param threshold 阈值百分比（0.0-1.0），默认0.70，表示70%的像素是同一颜色
     * @param sampleRate 采样率，默认10，每10个像素采样一次
     * @return 返回true表示左侧或右侧任一区域检测到纯色（可能是页面切换中）
     */
    fun isEitherSideMostlySingleColor(
        bitmap: Bitmap,
        threshold: Float = 0.70f,
        sampleRate: Int = 10
    ): Boolean {
        // 检测左侧或右侧任一区域是否纯色
        return isSideRegionMostlySingleColor(bitmap, threshold, sampleRate, checkLeft = true) ||
                isSideRegionMostlySingleColor(bitmap, threshold, sampleRate, checkLeft = false)
    }

    fun bitmapToJpegBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        var base64 = Base64.encodeToString(byteArray, Base64.DEFAULT)
        base64 = "data:image/jpeg;base64,$base64"
        return base64
    }

    /**
     * Strip a `data:<mime>;base64,` prefix and any surrounding whitespace/newlines
     * from a (possibly data-URL) base64 image string, returning raw base64 only.
     */
    fun stripBase64Prefix(raw: String): String {
        val trimmed = raw.trim()
        val commaIndex = trimmed.indexOf(',')
        val withoutPrefix = if (trimmed.startsWith("data:", ignoreCase = true) && commaIndex > 0) {
            trimmed.substring(commaIndex + 1)
        } else {
            trimmed
        }
        // Remove all whitespace (Base64.DEFAULT wraps lines every 76 chars with '\n').
        return withoutPrefix.filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
    }

    /**
     * Normalize any screenshot string (data-URL or raw base64, JPEG/PNG, possibly
     * line-wrapped) into clean, prefix-free PNG base64 with NO_WRAP encoding.
     *
     * Google Gemini Computer Use requires images to be valid PNG base64 strings
     * (mime_type image/png) with no data-URL prefix and no embedded newlines, both
     * for input images and for function_result images. This re-encodes to guarantee
     * that contract regardless of how the source screenshot was produced.
     *
     * @return clean PNG base64, or null if [raw] is blank or cannot be decoded.
     */
    fun normalizeToPngBase64(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = stripBase64Prefix(raw)
        if (cleaned.isBlank()) return null
        val bytes = try {
            Base64.decode(cleaned, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun base64ToBitmap(base64: String): Bitmap {
        val byteArray = Base64.decode(base64.replace("data:image/jpeg;base64,", ""), Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
    }

    fun bitmapToFile(context: Context, bitmap: Bitmap): String {
        val fileName = "${Constants.SCREENSHOT_PREFIX}${System.currentTimeMillis()}.jpg"
        val file = File(Constants.SCREENSHOT_DIR, fileName)
        return ImageUtils.bitmapToFile(context, bitmap, file)
    }

    fun bitmapToFile(context: Context, bitmap: Bitmap, file: File): String {

        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 50, fos)
        }

        return file.absolutePath
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun convertToSoftwareBitmap(hardwareBitmap: Bitmap): Bitmap {
        if (hardwareBitmap.config == Bitmap.Config.HARDWARE) {
            return hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        return hardwareBitmap
    }



}

