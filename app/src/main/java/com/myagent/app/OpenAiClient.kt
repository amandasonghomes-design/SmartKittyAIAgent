package com.myagent.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Talks to the OpenAI Responses API (supports web search and our own tools). */
class OpenAiClient(private val http: OkHttpClient) {

    data class ToolCall(val callId: String, val name: String, val arguments: String)
    data class Reply(
        val id: String,
        val texts: List<String>,
        val calls: List<ToolCall>,
        val model: String = "",          // exact model version that answered
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val organization: String = "",   // which OpenAI account is billed
        val project: String = "",
        val images: List<String> = emptyList(), // base64 pictures from the image generation tool
    )

    suspend fun respond(
        apiKey: String,
        model: String,
        instructions: String,
        input: JSONArray,
        tools: JSONArray,
        previousResponseId: String?,
    ): Reply = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("tools", tools)
        if (previousResponseId != null) body.put("previous_response_id", previousResponseId)

        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    JSONObject(text).getJSONObject("error").getString("message")
                }.getOrDefault(text.take(300))
                throw IOException("OpenAI error ${resp.code}: $msg")
            }
            parse(JSONObject(text)).copy(
                organization = resp.header("openai-organization").orEmpty(),
                project = resp.header("openai-project").orEmpty(),
            )
        }
    }

    /** Turns texts into embeddings ("meaning numbers") for searching past chats. */
    suspend fun embed(apiKey: String, texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val body = JSONObject().put("model", EMBEDDING_MODEL).put("input", JSONArray(texts))
        val request = Request.Builder()
            .url("https://api.openai.com/v1/embeddings")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("OpenAI embeddings error ${resp.code}: ${text.take(200)}")
            val data = JSONObject(text).getJSONArray("data")
            List(data.length()) { i ->
                val arr = data.getJSONObject(i).getJSONArray("embedding")
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            }
        }
    }

    private fun parse(json: JSONObject): Reply {
        val texts = mutableListOf<String>()
        val calls = mutableListOf<ToolCall>()
        val images = mutableListOf<String>()
        val output = json.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            when (item.optString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val c = content.getJSONObject(j)
                        if (c.optString("type") == "output_text") texts += c.optString("text")
                    }
                }
                "image_generation_call" -> item.optString("result").takeIf { it.isNotBlank() }?.let { images += it }
                "function_call" -> calls += ToolCall(
                    callId = item.getString("call_id"),
                    name = item.getString("name"),
                    arguments = item.optString("arguments", "{}"),
                )
            }
        }
        val usage = json.optJSONObject("usage")
        return Reply(
            id = json.getString("id"),
            texts = texts.filter { it.isNotBlank() },
            calls = calls,
            images = images,
            model = json.optString("model"),
            inputTokens = usage?.optInt("input_tokens") ?: 0,
            outputTokens = usage?.optInt("output_tokens") ?: 0,
        )
    }

    companion object {
        const val EMBEDDING_MODEL = "text-embedding-3-small"
    }
}
