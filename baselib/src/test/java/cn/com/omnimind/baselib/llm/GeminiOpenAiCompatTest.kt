package cn.com.omnimind.baselib.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiOpenAiCompatTest {

    @Test
    fun `detects gemini routes by api base host`() {
        assertTrue(
            GeminiOpenAiCompat.isGeminiRoute(
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "gpt-4o"
            )
        )
        assertTrue(GeminiOpenAiCompat.isGeminiApiBase("https://generativelanguage.googleapis.com/v1beta/openai/"))
        assertFalse(GeminiOpenAiCompat.isGeminiApiBase("https://api.openai.com/v1"))
    }

    @Test
    fun `detects gemini routes by model name`() {
        assertTrue(GeminiOpenAiCompat.isGeminiRoute("https://api.openai.com/v1", "gemini-2.5-pro"))
        assertTrue(GeminiOpenAiCompat.isGeminiModel("google/gemini-3.5-flash"))
        assertTrue(GeminiOpenAiCompat.isGeminiModel("models/gemma-3-27b-it"))
        assertFalse(GeminiOpenAiCompat.isGeminiModel("deepseek-chat"))
        assertFalse(GeminiOpenAiCompat.isGeminiRoute("https://api.openai.com/v1", "gpt-4o"))
    }

    @Test
    fun `sanitize drops legacy and non-standard top-level fields`() {
        val request = ChatCompletionRequest(
            messages = emptyList(),
            model = "gemini-2.5-pro",
            functions = listOf(ChatCompletionFunction(name = "foo")),
            enableThinking = false,
            thinking = ChatCompletionThinking(type = "disabled"),
            reasoningEffort = "high"
        )

        val sanitized = GeminiOpenAiCompat.sanitizeRequest(request)

        assertNull(sanitized.functions)
        assertNull(sanitized.functionCall)
        assertNull(sanitized.enableThinking)
        assertNull(sanitized.thinking)
        // reasoning_effort is natively supported by Gemini and must be preserved.
        assertEquals("high", sanitized.reasoningEffort)
    }

    @Test
    fun `sanitize strips additionalProperties from nested tool schema`() {
        val parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("environment", buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", buildJsonObject { put("type", "string") })
                })
            })
        }
        val request = ChatCompletionRequest(
            messages = emptyList(),
            model = "gemini-2.5-pro",
            tools = listOf(
                ChatCompletionTool(function = ChatCompletionFunction(name = "shell", parameters = parameters))
            )
        )

        val sanitized = GeminiOpenAiCompat.sanitizeRequest(request)
        val encoded = Json.encodeToString(ChatCompletionRequest.serializer(), sanitized)

        assertFalse(encoded.contains("additionalProperties"))
        // The legitimate structure is preserved.
        assertTrue(encoded.contains("environment"))
    }

    @Test
    fun `sanitizeRequestBody is subtractive - strips bad fields without adding defaults`() {
        val body = buildJsonObject {
            put("model", "gemini-2.5-pro")
            put("stream", true)
            put("reasoning_effort", "high")
            put("enable_thinking", false)
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("functions", buildJsonArray { add(buildJsonObject { put("name", "legacy") }) })
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "shell")
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("env", buildJsonObject {
                                    put("type", "object")
                                    put("additionalProperties", buildJsonObject { put("type", "string") })
                                })
                            })
                        })
                    })
                })
            })
        }

        val cleaned = GeminiOpenAiCompat.sanitizeRequestBody(body)
        val encoded = Json.encodeToString(JsonObject.serializer(), cleaned)

        assertFalse(encoded.contains("enable_thinking"))
        assertFalse(encoded.contains("\"thinking\""))
        assertFalse(encoded.contains("\"functions\""))
        assertFalse(encoded.contains("additionalProperties"))
        // Standard fields are preserved untouched, and nothing new is injected.
        assertTrue(encoded.contains("reasoning_effort"))
        assertTrue(encoded.contains("shell"))
        assertFalse(encoded.contains("tool_choice"))
    }

    @Test
    fun `sanitize converts const to single value enum and removes unsupported keys`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("properties", buildJsonObject {
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("const", "fast")
                })
            })
        }

        val result: JsonObject = GeminiOpenAiCompat.sanitizeSchema(schema)
        val encoded = Json.encodeToString(JsonObject.serializer(), result)

        assertFalse(encoded.contains("\$schema"))
        assertFalse(encoded.contains("\"const\""))
        assertTrue(encoded.contains("enum"))
        // enum should carry the original const value.
        val mode = (result["properties"] as JsonObject)["mode"] as JsonObject
        assertEquals("fast", mode["enum"]!!.jsonArray.first().toString().trim('"'))
    }
}
