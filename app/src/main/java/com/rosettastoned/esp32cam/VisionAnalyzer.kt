package com.rosettastoned.esp32cam

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VisionAnalyzer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun analyze(apiKey: String, imageBytes: ByteArray, prompt: String): Result<String> {
        return try {
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            val content = JSONArray()
            content.put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                }
            )
            content.put(
                JSONObject().apply {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$base64Image")
                    )
                }
            )

            val payload = JSONObject().apply {
                put("model", "gpt-4o-mini")
                put("max_tokens", 400)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                }))
            }

            val body = payload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val message = runCatching {
                        JSONObject(text)
                            .getJSONObject("error")
                            .getString("message")
                    }.getOrNull() ?: "HTTP ${response.code}"
                    return Result.failure(Exception(message))
                }
                val answer = JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                Result.success(answer)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
