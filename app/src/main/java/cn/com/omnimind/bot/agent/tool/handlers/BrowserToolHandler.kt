package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.util.ImageUtils
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

class BrowserToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: cn.com.omnimind.bot.agent.AgentWorkspaceManager
) : ToolHandler {
    override val toolNames: Set<String> = setOf("browser_use")

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return executeBrowserUse(args, env, callback, toolHandle)
    }

    override suspend fun dispose() {
        LiveAgentBrowserSessionManager.releaseRunOwnership()
    }

    private suspend fun executeBrowserUse(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = "browser_use"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val request = BrowserUseRequest.fromJson(args)
            val engine = LiveAgentBrowserSessionManager.acquireEngine(
                context = helper.context,
                workspaceManager = workspaceManager,
                agentRunId = env.agentRunId,
                workspace = env.workspaceDescriptor
            )
            toolHandle.bindStopAction { engine.requestInterruptCurrentAction() }
            helper.reportToolProgress(callback, toolName, request.toolTitle, mapOf("summary" to request.toolTitle), toolHandle = toolHandle)

            val computerUseResult = executeGeminiComputerUseBrowserIfAvailable(
                request = request,
                engine = engine,
                env = env,
                toolName = toolName
            )
            if (computerUseResult != null) return computerUseResult

            val outcome = engine.execute(request)
            return buildBrowserResult(toolName, request, outcome, env)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "Browser operation failed")
        }
    }

    private fun shouldUseGeminiComputerUseForBrowser(request: BrowserUseRequest): Boolean {
        if (!HttpController.supportsGeminiComputerUse("scene.dispatch.model")) return false
        return when (request.action) {
            BrowserUseAction.NAVIGATE,
            BrowserUseAction.CLICK,
            BrowserUseAction.TYPE,
            BrowserUseAction.SCROLL,
            BrowserUseAction.GO_BACK,
            BrowserUseAction.GO_FORWARD,
            BrowserUseAction.PRESS_KEY,
            BrowserUseAction.HOVER -> true
            else -> false
        }
    }

    private suspend fun executeGeminiComputerUseBrowserIfAvailable(
        request: BrowserUseRequest,
        engine: BrowserUseEngine,
        env: AgentExecutionEnvironment,
        toolName: String
    ): ToolExecutionResult? {
        if (!shouldUseGeminiComputerUseForBrowser(request)) return null

        val initialScreenshot = captureBrowserComputerUseScreenshot(engine).getOrNull()
        val initialPrompt = buildGeminiComputerUseBrowserPrompt(request, env, engine.liveSessionSnapshot())
        val initialInput = mutableListOf<Map<String, Any?>>(mapOf("type" to "text", "text" to initialPrompt))
        initialScreenshot?.takeIf { it.base64.isNotBlank() }?.let {
            initialInput += mapOf("type" to "image", "data" to it.base64, "mime_type" to "image/png")
        }
        var interaction = HttpController.postGeminiComputerUseInteraction(
            modelOrScene = "scene.dispatch.model",
            environment = "browser",
            input = initialInput
        )
        val executedPayloads = mutableListOf<Map<String, Any?>>()
        var lastOutcome: BrowserUseOutcome? = null
        var screenshot = initialScreenshot ?: BrowserComputerUseScreenshot.empty()

        var loopIndex = 0
        while (loopIndex < 5) {
            val calls = interaction.functionCalls()
            if (calls.isEmpty()) break
            val call = calls.first()
            val outcome = executeGeminiComputerUseBrowserCall(engine, call, screenshot.width, screenshot.height)
            lastOutcome = outcome
            executedPayloads += linkedMapOf<String, Any?>(
                "computerUseAction" to call.name,
                "computerUseArguments" to call.arguments,
                "browserResult" to outcome.payload
            )
            screenshot = captureBrowserComputerUseScreenshot(engine).getOrElse { screenshot }
            val resultBlocks = mutableListOf<Map<String, Any?>>(
                mapOf("type" to "text", "text" to helper.encodeLocalizedPayload(outcome.payload))
            )
            // Computer Use requires a valid PNG image in the function response; only
            // attach it when we actually have clean PNG base64 to avoid 400s.
            if (screenshot.base64.isNotBlank()) {
                resultBlocks += mapOf("type" to "image", "data" to screenshot.base64, "mime_type" to "image/png")
            }
            interaction = HttpController.postGeminiComputerUseInteraction(
                modelOrScene = "scene.dispatch.model",
                environment = "browser",
                previousInteractionId = interaction.id,
                input = listOf(
                    mapOf(
                        "type" to "function_result",
                        "name" to call.name,
                        "call_id" to (call.id ?: call.name.orEmpty()),
                        "result" to resultBlocks
                    )
                )
            )
            loopIndex++
        }

        val finalOutcome = lastOutcome ?: return null
        val finalText = interaction.modelOutputText().ifBlank { finalOutcome.summaryText }
        val payload = linkedMapOf<String, Any?>(
            "toolTitle" to request.toolTitle,
            "geminiComputerUse" to true,
            "finalText" to finalText,
            "executedActions" to executedPayloads
        ).apply { putAll(finalOutcome.payload) }
        val encoded = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = helper.localized(finalText),
            previewJson = encoded,
            rawResultJson = encoded,
            success = true,
            imageDataUrl = screenshot.dataUrl,
            artifacts = finalOutcome.artifacts,
            workspaceId = env.workspaceDescriptor.id,
            actions = finalOutcome.actions
        )
    }

    private fun buildGeminiComputerUseBrowserPrompt(
        request: BrowserUseRequest,
        env: AgentExecutionEnvironment,
        snapshot: Map<String, Any?>
    ): String {
        return buildString {
            appendLine("You are controlling OpenOmniBot's embedded browser for the user's task.")
            appendLine("Use Gemini Computer Use browser actions. Coordinates you return are normalized 0-1000 relative to the screenshot.")
            appendLine("If the browser operation is complete, respond with a concise result and no function call.")
            appendLine()
            appendLine("User request: ${env.userMessage}")
            appendLine("Requested browser tool title: ${request.toolTitle}")
            appendLine("Requested browser action: ${request.action.wireName}")
            request.url?.let { appendLine("Requested URL: $it") }
            request.text?.let { appendLine("Requested text: $it") }
            request.selector?.let { appendLine("Requested selector: $it") }
            if (request.coordinateX != null && request.coordinateY != null) {
                appendLine("Requested coordinates: ${request.coordinateX}, ${request.coordinateY}")
            }
            appendLine("Browser state: ${helper.encodeLocalizedPayload(snapshot)}")
        }.trim()
    }

    private data class BrowserComputerUseScreenshot(
        val dataUrl: String,
        val base64: String,
        val mimeType: String,
        val width: Int,
        val height: Int
    ) {
        companion object {
            fun empty(): BrowserComputerUseScreenshot = BrowserComputerUseScreenshot(
                dataUrl = "",
                base64 = "",
                mimeType = "image/png",
                width = 1000,
                height = 1000
            )
        }
    }

    private suspend fun captureBrowserComputerUseScreenshot(
        engine: BrowserUseEngine
    ): Result<BrowserComputerUseScreenshot> = runCatching {
        val outcome = engine.execute(
            BrowserUseRequest(
                toolTitle = "Gemini Computer Use browser screenshot",
                action = BrowserUseAction.SCREENSHOT,
                readImage = true
            )
        )
        val dataUrl = requireNotNull(outcome.imageDataUrl) { "Browser screenshot did not include image data" }
        // Gemini Computer Use requires clean PNG base64 (no data-URL prefix, no line
        // wrapping). Re-encode whatever the browser produced (often JPEG) to PNG.
        val pngBase64 = ImageUtils.normalizeToPngBase64(dataUrl).orEmpty()
        BrowserComputerUseScreenshot(
            dataUrl = dataUrl,
            base64 = pngBase64,
            mimeType = "image/png",
            width = (outcome.payload["imageWidth"] as? Number)?.toInt() ?: 1000,
            height = (outcome.payload["imageHeight"] as? Number)?.toInt() ?: 1000
        )
    }

    private suspend fun executeGeminiComputerUseBrowserCall(
        engine: BrowserUseEngine,
        call: HttpController.GeminiComputerUseStep,
        screenshotWidth: Int,
        screenshotHeight: Int
    ): BrowserUseOutcome {
        val args = call.arguments
        val intent = args.stringArg("intent").ifBlank { "Gemini Computer Use ${call.name}" }
        fun x(default: Int = screenshotWidth / 2): Int = args.pixelArg("x", screenshotWidth, default)
        fun y(default: Int = screenshotHeight / 2): Int = args.pixelArg("y", screenshotHeight, default)
        return when (call.name) {
            "navigate", "open_web_browser" -> engine.execute(
                BrowserUseRequest(toolTitle = intent, action = BrowserUseAction.NAVIGATE, url = args.stringArg("url").ifBlank { args.stringArg("query") })
            )
            "search" -> engine.execute(
                BrowserUseRequest(toolTitle = intent, action = BrowserUseAction.NAVIGATE, url = "https://www.google.com/search?q=${java.net.URLEncoder.encode(args.stringArg("query"), "UTF-8")}")
            )
            "click", "click_at", "double_click", "triple_click", "middle_click", "right_click" -> engine.execute(
                BrowserUseRequest(toolTitle = intent, action = BrowserUseAction.CLICK, coordinateX = x(), coordinateY = y())
            )
            "hover_at", "move" -> engine.execute(
                BrowserUseRequest(toolTitle = intent, action = BrowserUseAction.HOVER, coordinateX = x(), coordinateY = y())
            )
            "type", "type_text_at" -> engine.execute(
                BrowserUseRequest(toolTitle = intent, action = BrowserUseAction.TYPE, text = args.stringArg("text"), coordinateX = x(), coordinateY = y())
            )
            "scroll_document", "scroll_at" -> engine.execute(
                BrowserUseRequest(
                    toolTitle = intent,
                    action = BrowserUseAction.SCROLL,
                    direction = args.stringArg("direction").ifBlank { "down" }.lowercase().let { if (it == "up") "up" else "down" },
                    amount = args.intArg("amount", args.intArg("magnitude", 700)).coerceIn(1, 20_000),
                    coordinateX = if (call.name == "scroll_at") x() else null,
                    coordinateY = if (call.name == "scroll_at") y() else null
                )
            )
            "go_back" -> engine.execute(BrowserUseRequest(toolTitle = intent, action = BrowserUseAction.GO_BACK))
            "go_forward" -> engine.execute(BrowserUseRequest(toolTitle = intent, action = BrowserUseAction.GO_FORWARD))
            "key_combination" -> engine.execute(
                BrowserUseRequest(toolTitle = intent, action = BrowserUseAction.PRESS_KEY, key = args.stringArg("keys").ifBlank { args.stringArg("key") })
            )
            "take_screenshot" -> engine.execute(
                BrowserUseRequest(toolTitle = intent, action = BrowserUseAction.SCREENSHOT, readImage = true)
            )
            "wait", "wait_5_seconds" -> {
                delay(if (call.name == "wait_5_seconds") 5000L else args.longArg("seconds", 1L).coerceIn(1L, 10L) * 1000L)
                engine.execute(BrowserUseRequest(toolTitle = intent, action = BrowserUseAction.GET_PAGE_INFO))
            }
            else -> BrowserUseOutcome(
                summaryText = intent,
                payload = mapOf("unsupportedComputerUseAction" to call.name, "arguments" to args)
            )
        }
    }

    private fun buildBrowserResult(
        toolName: String,
        request: BrowserUseRequest,
        outcome: BrowserUseOutcome,
        env: AgentExecutionEnvironment
    ): ToolExecutionResult.ContextResult {
        val payload = linkedMapOf<String, Any?>("toolTitle" to request.toolTitle).apply { putAll(outcome.payload) }
        val encoded = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = helper.localized(outcome.summaryText),
            previewJson = encoded,
            rawResultJson = encoded,
            success = true,
            imageDataUrl = outcome.imageDataUrl,
            artifacts = outcome.artifacts,
            workspaceId = env.workspaceDescriptor.id,
            actions = outcome.actions
        )
    }

    private fun Map<String, Any?>.stringArg(name: String, default: String = ""): String {
        return (this[name] as? String)?.trim() ?: this[name]?.toString()?.trim() ?: default
    }

    private fun Map<String, Any?>.intArg(name: String, default: Int = 0): Int {
        return when (val value = this[name]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        } ?: default
    }

    private fun Map<String, Any?>.longArg(name: String, default: Long = 0L): Long {
        return when (val value = this[name]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: default
    }

    private fun Map<String, Any?>.pixelArg(name: String, size: Int, default: Int): Int {
        val value = when (val raw = this[name]) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        } ?: return default
        val mapped = when {
            value <= 1.0 -> value * size
            value <= 1000.0 -> value / 1000.0 * size
            else -> value
        }
        return mapped.toInt().coerceIn(0, size.coerceAtLeast(1))
    }
}
