package cn.com.omnimind.baselib.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.net.URI

/**
 * Compatibility shim for Google's Gemini "OpenAI compatibility" endpoint
 * (https://generativelanguage.googleapis.com/v1beta/openai/).
 *
 * Unlike OpenAI itself, that endpoint enforces a *strict* schema: it rejects any
 * unknown top-level field and any JSON-Schema keyword outside the OpenAPI subset
 * Gemini understands, returning:
 *
 *   400 Invalid JSON payload received. Unknown name "<field>": Cannot find field.
 *
 * Because the app routes Gemini through the generic `openai_compatible` path
 * (there is no dedicated Gemini protocol), OpenAI-only constructs leak into the
 * request body and trip this validation. This object normalizes a
 * [ChatCompletionRequest] into the subset Gemini accepts.
 */
object GeminiOpenAiCompat {

    private const val GEMINI_HOST_SUFFIX = "generativelanguage.googleapis.com"

    /** Top-level request fields Gemini's OpenAI layer does not recognize. */
    // (handled structurally in [sanitizeRequest] via copy(...) = null)

    /**
     * JSON-Schema keywords that Gemini's function-declaration schema (an OpenAPI
     * 3.0 subset) does not accept. Sending any of these inside `tools[].function.parameters`
     * causes the strict endpoint to reject the whole request.
     */
    private val UNSUPPORTED_SCHEMA_KEYS = setOf(
        "additionalProperties",
        "patternProperties",
        "additionalItems",
        "unevaluatedProperties",
        "unevaluatedItems",
        "propertyNames",
        "allOf",
        "oneOf",
        "not",
        "\$schema",
        "\$id",
        "\$ref",
        "\$defs",
        "\$anchor",
        "\$comment",
        "definitions",
        "examples"
    )

    /** Schema keys whose values are themselves a single nested schema. */
    private val NESTED_SCHEMA_KEYS = setOf("items", "contains", "if", "then", "else")

    /** Schema keys whose values are an array of nested schemas. */
    private val NESTED_SCHEMA_ARRAY_KEYS = setOf("anyOf", "prefixItems")

    fun isGeminiRoute(apiBase: String?, vararg models: String?): Boolean {
        if (isGeminiApiBase(apiBase)) return true
        return models.any { isGeminiModel(it) }
    }

    fun isGeminiApiBase(apiBase: String?): Boolean {
        val normalized = apiBase?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val host = runCatching { URI(normalized).host?.lowercase() }
            .getOrNull()
            ?.removePrefix("www.")
            ?: return false
        return host == GEMINI_HOST_SUFFIX || host.endsWith(".$GEMINI_HOST_SUFFIX")
    }

    fun isGeminiModel(model: String?): Boolean {
        val normalized = model?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        return normalized.contains("gemini") || normalized.contains("gemma")
    }

    /**
     * Normalize a request for Gemini's OpenAI-compatible endpoint:
     *  - drop deprecated/legacy `functions` + `function_call`,
     *  - drop non-standard `enable_thinking` / `thinking` (Gemini uses `reasoning_effort`),
     *  - recursively strip unsupported JSON-Schema keywords from every tool's parameters.
     */
    fun sanitizeRequest(request: ChatCompletionRequest): ChatCompletionRequest {
        val sanitizedTools = request.tools.map { tool ->
            tool.copy(
                function = tool.function.copy(
                    parameters = sanitizeSchema(tool.function.parameters)
                )
            )
        }
        return request.copy(
            tools = sanitizedTools,
            functions = null,
            functionCall = null,
            enableThinking = null,
            thinking = null
        )
    }

    /**
     * Purely subtractive sanitizer that operates directly on an already-serialized
     * request body (JSON tree). Unlike [sanitizeRequest] it never adds default
     * fields, so it is safe to apply at the lowest send choke point for *any*
     * caller (agent loop, voice, memory, etc.). It only:
     *  - removes the unsupported top-level fields, and
     *  - sanitizes the JSON-Schema of every `tools[].function.parameters`.
     */
    fun sanitizeRequestBody(body: JsonObject): JsonObject {
        return buildJsonObject {
            for ((key, value) in body) {
                when (key) {
                    "functions", "function_call", "enable_thinking", "thinking" -> continue
                    "tools" -> if (value is JsonArray) put("tools", sanitizeToolsArray(value)) else put(key, value)
                    "messages" -> if (value is JsonArray) put("messages", relocateImagePartsForGemini(value)) else put(key, value)
                    else -> put(key, value)
                }
            }
        }
    }

    /**
     * Gemini's OpenAI-compatible endpoint only accepts `image_url` content parts
     * inside **user** messages. OpenAI (and OpenOmniBot's browser/screenshot flow)
     * happily attach images to `tool` result and `assistant` messages, which Gemini
     * rejects with `400 Invalid content part type: image_url`.
     *
     * This relocates any image part out of a non-user message into a brand-new
     * `user` message inserted right after it (verified to return 200, model still
     * "sees" the image). The original message keeps its text parts; if that would
     * leave it empty, a short placeholder text is inserted so the role stays valid.
     */
    private fun relocateImagePartsForGemini(messages: JsonArray): JsonArray {
        val resultList = mutableListOf<JsonObject>()
        val pendingImages = mutableListOf<JsonElement>()

        for (i in 0 until messages.size) {
            val msg = messages[i]
            if (msg !is JsonObject) {
                if (pendingImages.isNotEmpty()) {
                    resultList.add(buildUserImageMessage(pendingImages))
                    pendingImages.clear()
                }
                continue
            }

            val role = (msg["role"] as? JsonPrimitive)?.content
            val content = msg["content"]

            // Flush pending images if we hit a user message, or transition from a tool message to a non-tool message.
            if (pendingImages.isNotEmpty()) {
                val prevMsg = resultList.lastOrNull()
                val prevRole = (prevMsg?.get("role") as? JsonPrimitive)?.content
                if (role == "user" || (prevRole == "tool" && role != "tool")) {
                    resultList.add(buildUserImageMessage(pendingImages))
                    pendingImages.clear()
                }
            }

            // Only user messages may carry images; nothing to do for those or for string/null content.
            if (role == null || role == "user" || content !is JsonArray) {
                resultList.add(msg)
                continue
            }

            val imageParts = content.filter { isImageContentPart(it) }
            if (imageParts.isEmpty()) {
                resultList.add(msg)
                continue
            }

            val textParts = content.filterNot { isImageContentPart(it) }
            pendingImages.addAll(imageParts)

            // Rebuild the original message without the image parts.
            resultList.add(
                buildJsonObject {
                    for ((k, v) in msg) {
                        if (k == "content") {
                            if (textParts.isEmpty()) {
                                put("content", buildJsonArray { add(textContentPart("[image provided in the following message]")) })
                            } else {
                                put("content", buildJsonArray { textParts.forEach { add(it) } })
                            }
                        } else {
                            put(k, v)
                        }
                    }
                }
            )
        }

        if (pendingImages.isNotEmpty()) {
            resultList.add(buildUserImageMessage(pendingImages))
        }

        return buildJsonArray {
            resultList.forEach { add(it) }
        }
    }

    private fun buildUserImageMessage(imageParts: List<JsonElement>): JsonObject {
        return buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("content", buildJsonArray {
                add(textContentPart("Image output from the previous tool call:"))
                imageParts.forEach { add(it) }
            })
        }
    }

    private fun isImageContentPart(part: JsonElement): Boolean {
        if (part !is JsonObject) return false
        val type = (part["type"] as? JsonPrimitive)?.content
        return type == "image_url" || type == "input_image" || type == "image"
    }

    private fun textContentPart(text: String): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(text))
        }
    }

    private fun sanitizeToolsArray(tools: JsonArray): JsonArray {
        return buildJsonArray {
            for (tool in tools) {
                if (tool !is JsonObject) {
                    add(tool)
                    continue
                }
                add(
                    buildJsonObject {
                        for ((key, value) in tool) {
                            if (key == "function" && value is JsonObject) {
                                put("function", sanitizeFunctionObject(value))
                            } else {
                                put(key, value)
                            }
                        }
                    }
                )
            }
        }
    }

    private fun sanitizeFunctionObject(function: JsonObject): JsonObject {
        return buildJsonObject {
            for ((key, value) in function) {
                if (key == "parameters" && value is JsonObject) {
                    put("parameters", sanitizeSchema(value))
                } else {
                    put(key, value)
                }
            }
        }
    }

    /** Recursively remove Gemini-incompatible keywords from a JSON-Schema object. */
    fun sanitizeSchema(schema: JsonObject): JsonObject {
        return buildJsonObject {
            for ((key, value) in schema) {
                if (key in UNSUPPORTED_SCHEMA_KEYS) {
                    continue
                }
                when {
                    // `const` -> single-value `enum` (Gemini supports enum, not const).
                    key == "const" -> {
                        if (schema["enum"] == null) {
                            put("enum", buildJsonArray { add(value) })
                        }
                    }

                    key == "properties" && value is JsonObject -> {
                        put(
                            "properties",
                            buildJsonObject {
                                for ((propName, propSchema) in value) {
                                    put(propName, sanitizeElement(propSchema))
                                }
                            }
                        )
                    }

                    key in NESTED_SCHEMA_KEYS -> put(key, sanitizeElement(value))

                    key in NESTED_SCHEMA_ARRAY_KEYS && value is JsonArray -> {
                        put(key, buildJsonArray { value.forEach { add(sanitizeElement(it)) } })
                    }

                    else -> put(key, value)
                }
            }
        }
    }

    private fun sanitizeElement(element: JsonElement): JsonElement {
        return if (element is JsonObject) sanitizeSchema(element) else element
    }
}
