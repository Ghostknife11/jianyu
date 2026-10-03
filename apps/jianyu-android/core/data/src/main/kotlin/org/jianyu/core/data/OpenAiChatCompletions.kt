package org.jianyu.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.Reader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Wire format shared by discovery and the explicitly requested public-data probe. */
internal object OpenAiChatCompletions {
    fun request(
        settings: AiProviderSettings,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ): String {
        settings.validate()
        val endpoint = settings.baseUrl.trimEnd('/').let {
            if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
        }
        val requestBody = buildJsonObject {
            put("model", settings.model)
            put("temperature", temperature)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }.toString()
        val connection = connectionFactory(URL(endpoint))
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(requestBody.encodeToByteArray()) }
            val status = connection.responseCode
            if (status !in 200..299) throw AiChatHttpException(status)
            val body = connection.inputStream.bufferedReader().use { it.readLimited(MAX_RESPONSE_CHARS) }
            parseContent(body)
        } catch (_: SocketTimeoutException) {
            throw AiChatTimeoutException()
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseContent(body: String): String {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw AiChatResponseFormatException()
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw AiChatResponseFormatException()
        // Older compatible services may omit the field; a reported non-stop finish is never a complete answer.
        choice["finish_reason"]?.let { finish ->
            if ((finish as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull != "stop") {
                throw AiChatIncompleteException()
            }
        }
        return (choice["message"] as? JsonObject)
            ?.get("content")
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?: throw AiChatResponseFormatException()
    }

    private const val MAX_RESPONSE_CHARS = 2_000_000
}

internal class AiChatHttpException(val status: Int) : Exception("AI 服务返回 HTTP $status")
internal class AiChatTimeoutException : Exception("等待 AI 服务响应超时")
internal class AiChatIncompleteException : Exception("AI 服务没有完整返回内容")
internal class AiChatResponseFormatException : Exception("AI 服务的响应格式无法读取")

private fun Reader.readLimited(maxChars: Int): String {
    val buffer = CharArray(8_192)
    val output = StringBuilder()
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (output.length + read > maxChars) throw AiChatResponseFormatException()
        output.append(buffer, 0, read)
    }
    return output.toString()
}
