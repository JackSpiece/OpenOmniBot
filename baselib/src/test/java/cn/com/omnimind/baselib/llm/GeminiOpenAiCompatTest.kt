package cn.com.omnimind.baselib.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    @Test
    fun `sanitizeRequestBody preserves gemini thought_signature in message tool_calls`() {
        // Gemini 3 requires the thought_signature (carried in extra_content) to be
        // replayed in history; the sanitizer must never strip message content.
        val body = buildJsonObject {
            put("model", "gemini-3.5-flash")
            put("enable_thinking", true)
            put("reasoning_effort", "high")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("tool_calls", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "fc1")
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", "get_weather")
                                put("arguments", "{}")
                            })
                            put("extra_content", buildJsonObject {
                                put("google", buildJsonObject {
                                    put("thought_signature", "SIG_ABC123")
                                })
                            })
                        })
                    })
                })
            })
        }

        val result = GeminiOpenAiCompat.sanitizeRequestBody(body)
        val encoded = Json.encodeToString(JsonObject.serializer(), result)

        // Incompatible field stripped, thinking control kept, signature preserved.
        assertFalse(encoded.contains("enable_thinking"))
        assertTrue(encoded.contains("\"reasoning_effort\":\"high\""))
        assertTrue(encoded.contains("extra_content"))
        assertTrue(encoded.contains("SIG_ABC123"))
    }

    @Test
    fun `sanitizeRequestBody relocates image parts out of tool messages into a user message`() {
        // Gemini's OpenAI-compat endpoint returns 400 "Invalid content part type:
        // image_url" when an image lives in a tool/assistant message (the browser
        // screenshot flow). Images must be moved into a user message.
        val body = buildJsonObject {
            put("model", "gemini-3.5-flash")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", "c1")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "screenshot captured")
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/png;base64,AAA")
                            })
                        })
                    })
                })
            })
        }

        val result = GeminiOpenAiCompat.sanitizeRequestBody(body)
        val messages = result["messages"]!!.jsonArray

        // One tool message in -> tool (text only) + injected user (image) out.
        assertEquals(2, messages.size)
        val toolMsg = messages[0] as JsonObject
        assertEquals("tool", (toolMsg["role"] as JsonPrimitive).content)
        val toolEncoded = Json.encodeToString(JsonObject.serializer(), toolMsg)
        assertFalse(toolEncoded.contains("image_url"))
        assertTrue(toolEncoded.contains("screenshot captured"))

        val userMsg = messages[1] as JsonObject
        assertEquals("user", (userMsg["role"] as JsonPrimitive).content)
        val userEncoded = Json.encodeToString(JsonObject.serializer(), userMsg)
        assertTrue(userEncoded.contains("image_url"))
        assertTrue(userEncoded.contains("data:image/png;base64,AAA"))
    }

    @Test
    fun `sanitizeRequestBody keeps multiple tool messages contiguous and defers image relocation`() {
        val body = buildJsonObject {
            put("model", "gemini-3.5-flash")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", "c1")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "screenshot captured")
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/png;base64,AAA")
                            })
                        })
                    })
                })
                add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", "c2")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "another tool result")
                        })
                    })
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", "final response")
                })
            })
        }

        val result = GeminiOpenAiCompat.sanitizeRequestBody(body)
        val messages = result["messages"]!!.jsonArray

        // Total messages should be 4:
        // 1. tool c1 (no image)
        // 2. tool c2 (no image)
        // 3. user (injected with image)
        // 4. assistant
        assertEquals(4, messages.size)

        val msg0 = messages[0] as JsonObject
        assertEquals("tool", (msg0["role"] as JsonPrimitive).content)
        assertEquals("c1", (msg0["tool_call_id"] as JsonPrimitive).content)

        val msg1 = messages[1] as JsonObject
        assertEquals("tool", (msg1["role"] as JsonPrimitive).content)
        assertEquals("c2", (msg1["tool_call_id"] as JsonPrimitive).content)

        val msg2 = messages[2] as JsonObject
        assertEquals("user", (msg2["role"] as JsonPrimitive).content)
        val userEncoded = Json.encodeToString(JsonObject.serializer(), msg2)
        assertTrue(userEncoded.contains("image_url"))

        val msg3 = messages[3] as JsonObject
        assertEquals("assistant", (msg3["role"] as JsonPrimitive).content)
        assertEquals("final response", (msg3["content"] as JsonPrimitive).content)
    }
}
