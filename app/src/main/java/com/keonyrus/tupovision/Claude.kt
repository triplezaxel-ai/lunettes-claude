package com.keonyrus.tupovision

import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ApiException(val status: Int, message: String) : IOException(message)

/** One finished exchange kept as text so follow-up questions have context. */
data class Turn(val question: String, val answer: String)

/** What one answer cost, as reported by the API. */
data class Usage(val inputTokens: Int, val outputTokens: Int)

/** A piece of the answer, a request to use a tool, or, at the end, the token count. */
sealed class ClaudeEvent {
  data class Text(val delta: String) : ClaudeEvent()
  data class ToolUse(val id: String, val name: String) : ClaudeEvent()
  data class Done(val usage: Usage, val stopReason: String) : ClaudeEvent()
}

/**
 * The Anthropic Messages API, streamed, with an API key. The key stays on the phone and goes only
 * to api.anthropic.com. The camera is a tool: Claude asks for a picture only when it needs one.
 */
object Claude {
  private const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
  private const val API_VERSION = "2023-06-01"
  const val LOOK_TOOL = "regarder"

  /** The one tool: a picture of what the wearer is looking at, taken when Claude calls it. */
  fun tools(): JSONArray =
      JSONArray()
          .put(
              JSONObject()
                  .put("name", LOOK_TOOL)
                  .put(
                      "description",
                      "Prend une photo de ce que l'utilisateur a devant les yeux, avec la caméra " +
                          "de ses lunettes. À utiliser seulement quand la question porte sur ce " +
                          "qu'il voit, ce qu'il tient, ce qu'il doit lire, identifier ou réparer " +
                          "devant lui. Ne pas l'utiliser pour une question générale.",
                  )
                  .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject())),
          )

  fun textMessage(role: String, text: String): JSONObject =
      JSONObject().put("role", role).put("content", text)

  fun imageBlock(jpeg: ByteArray): JSONObject =
      JSONObject()
          .put("type", "image")
          .put(
              "source",
              JSONObject()
                  .put("type", "base64")
                  .put("media_type", "image/jpeg")
                  .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
          )

  /** The assistant turn that asked for the tool, replayed as the API expects it. */
  fun toolCallMessage(textBefore: String, id: String, name: String): JSONObject {
    val content = JSONArray()
    if (textBefore.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", textBefore))
    content.put(
        JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", JSONObject()),
    )
    return JSONObject().put("role", "assistant").put("content", content)
  }

  /** The picture, or the reason there is none, handed back to Claude. */
  fun toolResultMessage(id: String, jpeg: ByteArray?): JSONObject {
    val result = JSONObject().put("type", "tool_result").put("tool_use_id", id)
    if (jpeg != null) {
      result.put("content", JSONArray().put(imageBlock(jpeg)))
    } else {
      result.put("content", "La caméra des lunettes n'a pas pu fournir d'image.").put("is_error", true)
    }
    return JSONObject().put("role", "user").put("content", JSONArray().put(result))
  }

  fun buildBody(model: String, system: String, messages: JSONArray, maxTokens: Int): JSONObject =
      JSONObject()
          .put("model", model)
          .put("max_tokens", maxTokens)
          .put("system", system)
          .put("messages", messages)
          .put("tools", tools())
          .put("stream", true)

  fun stream(apiKey: String, body: JSONObject): Flow<ClaudeEvent> = channelFlow {
    val connectionRef = AtomicReference<HttpURLConnection?>(null)
    launch(Dispatchers.IO) {
      try {
        val connection = URL(MESSAGES_URL).openConnection() as HttpURLConnection
        connectionRef.set(connection)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", API_VERSION)
        connection.setRequestProperty("content-type", "application/json")
        connection.setRequestProperty("accept", "text/event-stream")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val status = connection.responseCode
        if (status !in 200..299) {
          val text = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
          throw ApiException(status, errorMessage(status, text))
        }

        var inputTokens = 0
        var outputTokens = 0
        var stopReason = ""
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
          readLoop@ while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty()) continue
            val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when (event.optString("type")) {
              "message_start" -> {
                val usage = event.optJSONObject("message")?.optJSONObject("usage")
                if (usage != null) {
                  inputTokens =
                      usage.optInt("input_tokens") +
                          usage.optInt("cache_read_input_tokens") +
                          usage.optInt("cache_creation_input_tokens")
                  outputTokens = usage.optInt("output_tokens")
                }
              }
              "content_block_start" -> {
                val block = event.optJSONObject("content_block")
                if (block?.optString("type") == "tool_use") {
                  send(ClaudeEvent.ToolUse(block.optString("id"), block.optString("name")))
                }
              }
              "content_block_delta" -> {
                val delta = event.optJSONObject("delta")
                if (delta?.optString("type") == "text_delta") {
                  send(ClaudeEvent.Text(delta.optString("text")))
                }
              }
              "message_delta" -> {
                val reason = event.optJSONObject("delta")?.optString("stop_reason").orEmpty()
                if (reason.isNotEmpty() && reason != "null") stopReason = reason
                val usage = event.optJSONObject("usage")
                if (usage != null && usage.has("output_tokens")) {
                  outputTokens = usage.optInt("output_tokens")
                }
              }
              "message_stop" -> break@readLoop
              "error" -> {
                val message = event.optJSONObject("error")?.optString("message").orEmpty()
                throw ApiException(0, message.ifEmpty { "Erreur du flux" })
              }
            }
          }
        }
        send(ClaudeEvent.Done(Usage(inputTokens, outputTokens), stopReason))
        close()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        close(e)
      }
    }
    awaitClose {
      // Closing a TLS socket touches the network, so it must not run on the main thread.
      val connection = connectionRef.get()
      if (connection != null) Thread { runCatching { connection.disconnect() } }.start()
    }
  }

  private fun errorMessage(status: Int, body: String): String {
    val parsed =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }
            .getOrDefault("")
    val detail = parsed.ifEmpty { body.take(200) }
    return "HTTP $status : $detail"
  }
}
