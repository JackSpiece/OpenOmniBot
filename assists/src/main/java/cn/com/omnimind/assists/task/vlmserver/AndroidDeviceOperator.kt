package cn.com.omnimind.assists.task.vlmserver

/**
 * Android设备操作器 - 基于现有的AccessibilityController实现
 */

import android.content.Context
import android.content.Intent
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.api.eventapi.ExecutionTaskEventApi
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.baselib.shizuku.ShizukuControlState
import cn.com.omnimind.baselib.shizuku.ControlMethod
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PrivacyBlockedException
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import cn.com.omnimind.baselib.util.ImageQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

class AndroidDeviceOperator(
    private val executionTaskEventApi: ExecutionTaskEventApi?,
    private val context: Context? = null
) : DeviceOperator {

    private val Tag = "AndroidDeviceOperator"

    /**
     * Hybrid "Shizuku power mode": when the user enabled it AND Shizuku is
     * granted, taps / long-press / text / keys are routed through Shizuku
     * (shell-level `input`), which reaches surfaces accessibility gestures
     * can't. Scrolling stays on accessibility for smoother flings. Every path
     * falls back to accessibility on failure, and reports which technique
     * actually ran so the floating window can show a live indicator.
     */
    private fun powerMode(): Boolean {
        val ctx = context ?: return false
        return ShizukuControlState.isShizukuPowerModeActive(ctx)
    }

    private suspend fun shizukuTap(x: Float, y: Float): Boolean {
        val ctx = context ?: return false
        return runCatching {
            ShizukuCapabilityManager.get(ctx).tap(x.toInt(), y.toInt()).success
        }.getOrDefault(false)
    }

    private suspend fun shizukuLongPress(x: Float, y: Float, duration: Long): Boolean {
        val ctx = context ?: return false
        return runCatching {
            ShizukuCapabilityManager.get(ctx)
                .longPress(x.toInt(), y.toInt(), duration.toInt().coerceAtLeast(300))
                .success
        }.getOrDefault(false)
    }

    private suspend fun shizukuSwipe(
        x1: Float, y1: Float, x2: Float, y2: Float, duration: Long
    ): Boolean {
        val ctx = context ?: return false
        return runCatching {
            ShizukuCapabilityManager.get(ctx)
                .swipe(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), duration.toInt())
                .success
        }.getOrDefault(false)
    }

    private suspend fun shizukuKey(key: String): Boolean {
        val ctx = context ?: return false
        return runCatching {
            ShizukuCapabilityManager.get(ctx).pressKeyEvent(key).success
        }.getOrDefault(false)
    }

    private suspend fun shizukuType(text: String): Boolean {
        val ctx = context ?: return false
        return runCatching {
            ShizukuCapabilityManager.get(ctx).inputText(text).success
        }.getOrDefault(false)
    }

    private fun reportMethod(method: ControlMethod) = ShizukuControlState.reportMethod(method)

    // ----- action runners: prefer Shizuku in power mode, fall back to accessibility -----

    private suspend fun runTap(x: Float, y: Float) {
        if (powerMode() && shizukuTap(x, y)) {
            reportMethod(ControlMethod.SHIZUKU); return
        }
        AccessibilityController.clickCoordinate(x, y)
        reportMethod(ControlMethod.ACCESSIBILITY)
    }

    private suspend fun runLongClick(x: Float, y: Float, duration: Long) {
        if (powerMode() && shizukuLongPress(x, y, duration)) {
            reportMethod(ControlMethod.SHIZUKU); return
        }
        AccessibilityController.longClickCoordinate(x, y, duration)
        reportMethod(ControlMethod.ACCESSIBILITY)
    }

    private suspend fun runScroll(
        x: Float, y: Float, direction: ScrollDirection, distance: Float, duration: Long,
        x2: Float, y2: Float
    ) {
        // Hybrid: accessibility first for smooth flings; Shizuku swipe as fallback.
        val ok = runCatching {
            AccessibilityController.scrollCoordinate(x, y, direction, distance, duration = duration)
            true
        }.getOrDefault(false)
        if (ok) {
            reportMethod(ControlMethod.ACCESSIBILITY); return
        }
        if (powerMode() && shizukuSwipe(x, y, x2, y2, duration.coerceAtLeast(1))) {
            reportMethod(ControlMethod.SHIZUKU); return
        }
        // surface the accessibility failure path
        AccessibilityController.scrollCoordinate(x, y, direction, distance, duration = duration)
        reportMethod(ControlMethod.ACCESSIBILITY)
    }

    // 存储最后一次截图的尺寸（传给VLM的图片）以及设备实际尺寸
    private var lastScreenshotWidth: Int = 1080
    private var lastScreenshotHeight: Int = 1920
    private var lastDisplayWidth: Int = 1080
    private var lastDisplayHeight: Int = 1920

    companion object {
        private var clipboardResultCallback: ((Boolean) -> Unit)? = null
        private var clipboardGetResultCallback: ((String?) -> Unit)? = null
        private const val CLIPBOARD_ACTIVITY_CLASS =
            "cn.com.omnimind.bot.activity.ClipboardHelperActivity"
        private const val EXTRA_TEXT = "clipboard_text"
        private const val EXTRA_OPERATION = "clipboard_operation"
        private const val OPERATION_COPY = "copy"
        private const val OPERATION_GET = "get"

        @JvmStatic
        fun notifyClipboardResult(success: Boolean) {
            clipboardResultCallback?.invoke(success)
            clipboardResultCallback = null
        }

        @JvmStatic
        fun notifyClipboardGetResult(text: String?) {
            clipboardGetResultCallback?.invoke(text)
            clipboardGetResultCallback = null
        }
    }

    override suspend fun clickCoordinate(x: Float, y: Float): OperationResult {
        return try {
            if (executionTaskEventApi != null) {
                executionTaskEventApi.clickCoordinate(x, y) {
                    runTap(x, y)
                }
            } else {
                runTap(x, y)
            }
            OperationResult(true, "点击坐标 ($x, $y) 成功", null)
        } catch (e: Exception) {
            OperationResult(false, "点击失败: ${e.message}", null)
        }
    }

    override suspend fun longClickCoordinate(x: Float, y: Float, duration: Long): OperationResult {
        return try {
            if (executionTaskEventApi != null) {
                executionTaskEventApi.longClickCoordinate(x, y) {
                    runLongClick(x, y, duration)
                }
            } else {
                runLongClick(x, y, duration)
            }
            OperationResult(true, "长按坐标 ($x, $y) 成功", null)
        } catch (e: Exception) {
            OperationResult(false, "长按失败: ${e.message}", null)
        }
    }

    override suspend fun inputText(text: String): OperationResult {
        // In power mode, prefer Shizuku typing (more reliable on stubborn fields).
        if (powerMode() && shizukuType(text)) {
            reportMethod(ControlMethod.SHIZUKU)
            return OperationResult(true, "通过 Shizuku 输入文本成功", null)
        }
        return try {
            if (executionTaskEventApi != null) {
                executionTaskEventApi.inputText() {
                    AccessibilityController.inputTextToFocusedNode(text)
                }
            } else {
                AccessibilityController.inputTextToFocusedNode(text)
            }
            reportMethod(ControlMethod.ACCESSIBILITY)
            OperationResult(true, "输入文本成功: $text", null)
        } catch (e: Exception) {
            val shizukuFallback = inputTextViaShizuku(text)
            if (shizukuFallback.success) {
                reportMethod(ControlMethod.SHIZUKU)
                return shizukuFallback
            }
            val shellFallback = inputTextViaShell(text)
            if (shellFallback.success) {
                return shellFallback
            }
            OperationResult(false, "输入失败: ${e.message}", null)
        }
    }

    override suspend fun pressHotKey(key: String): OperationResult {
        val normalized = key.trim().uppercase()
        // In power mode, prefer Shizuku keyevent (works on more surfaces).
        if (powerMode() && shizukuKey(normalized)) {
            reportMethod(ControlMethod.SHIZUKU)
            return OperationResult(true, "通过 Shizuku 按下 $normalized 成功", null)
        }
        return try {
            AccessibilityController.pressHotKey(normalized)
            reportMethod(ControlMethod.ACCESSIBILITY)
            OperationResult(true, "按下热键 $normalized 成功", null)
        } catch (primaryError: Exception) {
            if (normalized == "ENTER") {
                val shizukuFallback = pressEnterViaShizuku()
                if (shizukuFallback.success) {
                    reportMethod(ControlMethod.SHIZUKU)
                    return shizukuFallback
                }
                val fallback = pressEnterViaShell()
                if (fallback.success) {
                    return fallback
                }
            }
            OperationResult(false, "热键执行失败: ${primaryError.message}", null)
        }
    }

    private suspend fun inputTextViaShizuku(text: String): OperationResult {
        val ctx = context ?: return OperationResult(false, "Shizuku 不可用", null)
        return try {
            val result = ShizukuCapabilityManager.get(ctx).inputText(text)
            if (result.success) {
                OperationResult(true, "通过 Shizuku 输入文本成功", null)
            } else {
                OperationResult(false, "Shizuku 输入失败: ${result.message}", null)
            }
        } catch (e: Exception) {
            OmniLog.e(Tag, "inputTextViaShizuku failed: ${e.message}", e)
            OperationResult(false, "Shizuku 输入失败: ${e.message}", null)
        }
    }

    /**
     * 通过Shell命令输入文本（非接口方法，仅作为备用）
     */
    suspend fun inputTextViaShell(text: String): OperationResult {
        return try {
            OmniLog.d(Tag, "inputTextViaShell: $text")
            // 使用 shell 命令直接输入文本
            val escapedText = text
                .replace("\\", "\\\\")
                .replace(" ", "%s")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("&", "\\&")
                .replace("<", "\\<")
                .replace(">", "\\>")
                .replace("|", "\\|")
                .replace(";", "\\;")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("\n", " ")

            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "sh", "-c",
                    "input text '$escapedText'"
                )
            )
            val exitCode = process.waitFor()
            OmniLog.d(Tag, "input text shell exit code: $exitCode")

            if (exitCode == 0) {
                OperationResult(true, "Shell输入文本成功: $text", null)
            } else {
                OperationResult(false, "Shell输入失败, exit code: $exitCode", null)
            }
        } catch (e: Exception) {
            OmniLog.e(Tag, "inputTextViaShell failed: ${e.message}", e)
            OperationResult(false, "Shell输入失败: ${e.message}", null)
        }
    }

    private suspend fun pressEnterViaShizuku(): OperationResult {
        val ctx = context ?: return OperationResult(false, "Shizuku 不可用", null)
        return try {
            val result = ShizukuCapabilityManager.get(ctx).pressKeyEvent("ENTER")
            if (result.success) {
                OperationResult(true, "通过 Shizuku 按下 ENTER 成功", null)
            } else {
                OperationResult(false, "Shizuku 按下 ENTER 失败: ${result.message}", null)
            }
        } catch (e: Exception) {
            OperationResult(false, "Shizuku 按下 ENTER 失败: ${e.message}", null)
        }
    }

    private suspend fun pressEnterViaShell(): OperationResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 66"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                OperationResult(true, "通过Shell按下ENTER键成功", null)
            } else {
                OperationResult(false, "Shell按下ENTER失败, exit code: $exitCode", null)
            }
        } catch (e: Exception) {
            OperationResult(false, "Shell按下ENTER失败: ${e.message}", null)
        }
    }

    override suspend fun copyToClipboard(text: String): OperationResult {
        val ctx = context ?: return try {
            // 无 context 时回退到原方法
            AccessibilityController.copyToClipboard(text)
            OperationResult(true, "已复制到剪贴板", null)
        } catch (e: Exception) {
            OmniLog.e(Tag, "copyToClipboard failed: ${e.message}", e)
            OperationResult(false, "复制到剪贴板失败: ${e.message}", null)
        }

        return try {
            val success = withTimeoutOrNull(5000L) {
                suspendCancellableCoroutine { continuation ->
                    clipboardResultCallback = { result ->
                        if (continuation.isActive) continuation.resume(result)
                    }
                    try {
                        val intent = Intent().apply {
                            setClassName(ctx.packageName, CLIPBOARD_ACTIVITY_CLASS)
                            putExtra(EXTRA_TEXT, text)
                            putExtra(EXTRA_OPERATION, OPERATION_COPY)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        }
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        clipboardResultCallback = null
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false

            if (success) {
                OperationResult(true, "已复制到剪贴板", null)
            } else {
                OperationResult(false, "复制到剪贴板失败", null)
            }
        } catch (e: Exception) {
            OperationResult(false, "复制到剪贴板失败: ${e.message}", null)
        }
    }

    override suspend fun getClipboard(): String? {
        val ctx = context ?: return null
        return try {
            withTimeoutOrNull(5000L) {
                suspendCancellableCoroutine { continuation ->
                    clipboardGetResultCallback = { text ->
                        if (continuation.isActive) continuation.resume(text)
                    }
                    try {
                        val intent = Intent().apply {
                            setClassName(ctx.packageName, CLIPBOARD_ACTIVITY_CLASS)
                            putExtra(EXTRA_OPERATION, OPERATION_GET)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        }
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        clipboardGetResultCallback = null
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        } catch (e: Exception) {
            OmniLog.e(Tag, "getClipboard failed: ${e.message}")
            null
        }
    }

    override suspend fun slideCoordinate(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Long
    ): OperationResult {
        return try {
            val dx = x2 - x1
            val dy = y2 - y1
            val scrollDirection = if (abs(dy) > abs(dx)) {
                if (dy > 0) ScrollDirection.DOWN else ScrollDirection.UP
            } else {
                if (dx > 0) ScrollDirection.RIGHT else ScrollDirection.LEFT
            }

            val distance = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (executionTaskEventApi != null) {
                executionTaskEventApi.scrollCoordinate(
                    x1,
                    y1,
                    scrollDirection,
                    distance.toInt()
                ) {
                    runScroll(x1, y1, scrollDirection, distance, duration, x2, y2)
                }
            } else {
                runScroll(x1, y1, scrollDirection, distance, duration, x2, y2)
            }
            OperationResult(true, "滑动 ($x1, $y1) → ($x2, $y2) 成功", null)
        } catch (e: Exception) {
            OperationResult(false, "滑动失败: ${e.message}", null)
        }
    }

    override suspend fun goHome(): OperationResult {
        return try {
            if (executionTaskEventApi != null) {
                executionTaskEventApi.goHome {
                    runGoHome()
                }
            } else {
                runGoHome()
            }

            OperationResult(true, "返回桌面成功", null)
        } catch (e: Exception) {
            OperationResult(false, "返回桌面失败: ${e.message}", null)
        }
    }

    private suspend fun runGoHome() {
        if (powerMode() && shizukuKey("HOME")) {
            reportMethod(ControlMethod.SHIZUKU); return
        }
        AccessibilityController.goHome()
        reportMethod(ControlMethod.ACCESSIBILITY)
    }

    override suspend fun goBack(): OperationResult {
        return try {
            if (executionTaskEventApi != null) {
                executionTaskEventApi.goBack {
                    runGoBack()
                }
            } else {
                runGoBack()
            }
            OperationResult(true, "返回上一级成功", null)
        } catch (e: Exception) {
            OperationResult(false, "返回上一级失败: ${e.message}", null)
        }
    }

    private suspend fun runGoBack() {
        if (powerMode() && shizukuKey("BACK")) {
            reportMethod(ControlMethod.SHIZUKU); return
        }
        AccessibilityController.goBack()
        reportMethod(ControlMethod.ACCESSIBILITY)
    }

    /**
     * 启动应用
     */
    override suspend fun launchApplication(packageName: String): OperationResult {
        return try {

            AccessibilityController.launchApplication(packageName) { x, y ->

                if (executionTaskEventApi != null) {
                    executionTaskEventApi.clickCoordinate(x, y) {
                        AccessibilityController.clickCoordinate(x, y)
                    }
                } else {
                    AccessibilityController.clickCoordinate(x, y)
                }
            }
            OperationResult(true, "启动应用 $packageName 成功", null)
        } catch (e: PrivacyBlockedException) {
            // 隐私限制异常需要终止任务，重新抛出
            throw e
        } catch (e: Exception) {
            val shizukuFallback = launchApplicationViaShizuku(packageName)
            if (shizukuFallback.success) {
                return shizukuFallback
            }
            OperationResult(false, "启动应用失败: ${e.message}", null)
        }
    }

    private suspend fun launchApplicationViaShizuku(packageName: String): OperationResult {
        val ctx = context ?: return OperationResult(false, "Shizuku 不可用", null)
        return try {
            val result = ShizukuCapabilityManager.get(ctx).launchApp(packageName)
            if (result.success) {
                OperationResult(true, "通过 Shizuku 启动应用成功", null)
            } else {
                OperationResult(false, "Shizuku 启动应用失败: ${result.message}", null)
            }
        } catch (e: Exception) {
            OperationResult(false, "Shizuku 启动应用失败: ${e.message}", null)
        }
    }

    /**
     * 捕获截图并返回Base64编码字符串
     */
    override suspend fun captureScreenshot(): String {
        return try {
            val start = System.currentTimeMillis()
            val payload = AccessibilityController.captureScreenshotImage(
                isFilterOverlay = true,
                isBase64 = true,
                compressQuality = ImageQuality.MEDIUM
            )
            if (!payload.isSuccess) {
                throw RuntimeException("截图数据为空")
            }
            val finalBase64 = payload.imageBase64!!
            val appliedScale = payload.appliedScale

            // 直接使用 CaptureData 中的尺寸信息
            lastScreenshotWidth = payload.compressedWidth
            lastScreenshotHeight = payload.compressedHeight

            val displayMetrics = context?.resources?.displayMetrics
            val metricsWidth = displayMetrics?.widthPixels ?: payload.originalWidth
            val metricsHeight = displayMetrics?.heightPixels ?: payload.originalHeight

            // 取更大的值避免低估（屏幕实测/截图原始值）
            lastDisplayWidth = maxOf(payload.originalWidth, metricsWidth)
            lastDisplayHeight = maxOf(payload.originalHeight, metricsHeight)

            OmniLog.d(
                Tag,
                "captureScreenshot cost ${System.currentTimeMillis() - start}ms, scale=$appliedScale"
            )
            OmniLog.d(
                Tag,
                "screenshot=${lastScreenshotWidth}x${lastScreenshotHeight}, originalDisplay=${payload.originalWidth}x${payload.originalHeight},metrics=${metricsWidth}x${metricsHeight}, chosenDisplay=${lastDisplayWidth}x${lastDisplayHeight}"
            )

            finalBase64
        } catch (e: Exception) {
            OmniLog.e("Assists", "captureScreenshot failed: ${e.message}", e)
            throw RuntimeException("截图失败: ${e.message}")
        }
    }

    override fun getLastScreenshotWidth(): Int = lastScreenshotWidth

    override fun getLastScreenshotHeight(): Int = lastScreenshotHeight

    override fun getDisplayWidth(): Int = lastDisplayWidth

    override fun getDisplayHeight(): Int = lastDisplayHeight
    override suspend fun showInfo(message: String) {
        executionTaskEventApi?.updateShowStepText(message)
    }

}
