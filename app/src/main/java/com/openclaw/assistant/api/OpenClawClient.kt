package com.openclaw.assistant.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Simple webhook client - POSTs to the configured URL
 */
class OpenClawClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * POST message to OpenResponses API endpoint and return response
     */
    suspend fun sendMessage(
        webhookUrl: String,
        message: String,
        sessionId: String,
        authToken: String? = null
    ): Result<OpenClawResponse> = withContext(Dispatchers.IO) {
        try {
            // OpenResponses API format for /v1/responses
            val requestBody = JsonObject().apply {
                addProperty("model", "openclaw")
                addProperty("input", message)
                addProperty("user", sessionId)
            }

            val jsonBody = gson.toJson(requestBody)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
                .url(webhookUrl)  // Use URL as-is (should be /v1/responses)
                .post(jsonBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: response.message
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: $errorBody")
                    )
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IOException("Empty response")
                    )
                }

                // Extract response text from JSON
                val text = extractResponseText(responseBody)
                Result.success(OpenClawResponse(response = text ?: responseBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Test connection to the webhook
     */
    suspend fun testConnection(
        webhookUrl: String,
        authToken: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Try a HEAD request first (lightweight)
            var requestBuilder = Request.Builder()
                .url(webhookUrl)
                .head()

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            var request = requestBuilder.build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return@withContext Result.success(true)
                    // If Method Not Allowed (405), try POST
                    if (response.code == 405) {
                         // Fallthrough to POST
                    } else {
                         return@withContext Result.failure(IOException("HTTP ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                // Fallthrough to POST on error (some servers reject HEAD)
            }

            // Fallback: POST with OpenResponses format
            val requestBody = JsonObject().apply {
                addProperty("model", "openclaw")
                addProperty("input", "ping")
                addProperty("user", "test-connection")
            }
            
            val jsonBody = gson.toJson(requestBody)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            requestBuilder = Request.Builder()
                .url(webhookUrl)
                .post(jsonBody)
                .addHeader("Content-Type", "application/json")

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            request = requestBuilder.build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    val errorBody = response.body?.string() ?: response.message
                    Result.failure(IOException("HTTP ${response.code}: $errorBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Extract text from OpenResponses API output format
     */
    private fun extractOpenResponsesText(obj: JsonObject): String? {
        val output = obj.getAsJsonArray("output") ?: return null
        for (item in output) {
            val itemObj = item.asJsonObject
            if (itemObj.get("type")?.asString == "message") {
                val content = itemObj.getAsJsonArray("content") ?: continue
                for (part in content) {
                    val partObj = part.asJsonObject
                    if (partObj.get("type")?.asString == "output_text") {
                        return partObj.get("text")?.asString
                    }
                }
            }
        }
        return null
    }

    /**
     * Extract response text from various JSON formats
     */
    private fun extractResponseText(json: String): String? {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            
            // OpenResponses API format: output[0].content[0].text
            extractOpenResponsesText(obj)
            // OpenClaw /hooks/voice format: { ok, response, session_id }
            ?: obj.get("response")?.asString
            // OpenAI format: choices[0].message.content
            ?: obj.getAsJsonArray("choices")?.let { choices ->
                choices.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
            }
            // Other simple formats
            ?: obj.get("text")?.asString
            ?: obj.get("message")?.asString
            ?: obj.get("content")?.asString
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Response wrapper
 */
data class OpenClawResponse(
    val response: String? = null,
    val error: String? = null
) {
    fun getResponseText(): String? = response
}
