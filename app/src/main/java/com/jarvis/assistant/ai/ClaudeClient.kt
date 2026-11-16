package com.jarvis.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ClaudeException(
    val kind: Kind,
    message: String,
    val retryAfterMs: Long? = null
) : Exception(message) {

    enum class Kind { NO_KEY, INVALID_KEY, RATE_LIMIT, OVERLOADED, NETWORK, SERVER, BAD_REQUEST, UNKNOWN }

    val retryable: Boolean
        get() = kind == Kind.RATE_LIMIT || kind == Kind.OVERLOADED || kind == Kind.SERVER || kind == Kind.NETWORK

    /** Mensagem curta em português, pensada para ser lida em voz alta. */
    fun userMessage(): String = when (kind) {
        Kind.NO_KEY -> "Falta a chave da API do Claude. Abra o app Jarvis e cole a chave."
        Kind.INVALID_KEY -> "A chave da API foi recusada. Confira se ela está correta e ativa."
        Kind.RATE_LIMIT -> "Muitas requisições agora. Tente de novo em instantes."
        Kind.OVERLOADED -> "O serviço do Claude está sobrecarregado. Tente de novo em instantes."
        Kind.NETWORK -> "Sem conexão com a internet."
        Kind.SERVER -> "O serviço do Claude está com problemas. Tente novamente em instantes."
        Kind.BAD_REQUEST -> "O serviço recusou o pedido: ${message ?: "motivo desconhecido"}"
        Kind.UNKNOWN -> "Erro inesperado: ${message ?: "motivo desconhecido"}"
    }
}

data class ToolCall(val id: String, val name: String, val input: JSONObject)

/**
 * Resposta do modelo.
 * [assistantContent] é o conteúdo "limpo" (só blocos text/tool_use) pronto para voltar ao histórico.
 */
data class ClaudeReply(
    val text: String,
    val toolCalls: List<ToolCall>,
    val stopReason: String?,
    val assistantContent: JSONArray
)

/**
 * Cliente mínimo da Messages API da Anthropic (POST https://api.anthropic.com/v1/messages),
 * usando apenas HttpURLConnection e org.json (já incluídos no Android).
 */
class ClaudeClient(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String
) {

    suspend fun send(
        system: String,
        messages: JSONArray,
        tools: JSONArray,
        maxTokens: Int = 1024
    ): ClaudeReply {
        val apiKey = apiKeyProvider()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ClaudeException(ClaudeException.Kind.NO_KEY, "Chave da API ausente")

        val body = JSONObject()
            .put("model", modelProvider())
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)
        if (tools.length() > 0) body.put("tools", tools)
        val payload = body.toString()

        var attempt = 0
        while (true) {
            try {
                return parse(post(payload, apiKey))
            } catch (e: ClaudeException) {
                if (!e.retryable || attempt >= MAX_RETRIES) throw e
                delay(e.retryAfterMs ?: backoff(attempt))
            } catch (e: IOException) {
                if (attempt >= MAX_RETRIES) {
                    throw ClaudeException(ClaudeException.Kind.NETWORK, e.message ?: "falha de rede")
                }
                delay(backoff(attempt))
            }
            attempt++
        }
    }

    /** Teste rápido de conexão/chave. Lança [ClaudeException] se algo estiver errado. */
    suspend fun ping() {
        val msgs = JSONArray().put(
            JSONObject().put("role", "user").put("content", "Responda apenas: ok")
        )
        send("Responda apenas com a palavra ok.", msgs, JSONArray(), maxTokens = 16)
    }

    private fun backoff(attempt: Int): Long = 800L * (1L shl attempt)

    /**
     * Faz o POST em IO. Se a coroutine for cancelada (usuário tocou para interromper), a conexão
     * é fechada na hora para destravar a leitura bloqueante.
     */
    private suspend fun post(payload: String, apiKey: String): String = coroutineScope {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        val watcher = launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                try {
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { execute(conn, payload, apiKey) }
        } finally {
            watcher.cancel()
        }
    }

    private fun execute(conn: HttpURLConnection, payload: String, apiKey: String): String {
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", API_VERSION)
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code in 200..299) return text
        throw mapError(code, text, conn.getHeaderField("retry-after"))
    }

    private fun mapError(code: Int, body: String, retryAfter: String?): ClaudeException {
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
        val retryMs = retryAfter?.trim()?.toLongOrNull()?.takeIf { it in 1..10 }?.times(1000)
        return when (code) {
            401, 403 -> ClaudeException(ClaudeException.Kind.INVALID_KEY, detail.ifEmpty { "chave inválida" })
            429 -> ClaudeException(ClaudeException.Kind.RATE_LIMIT, detail.ifEmpty { "limite de requisições" }, retryMs)
            529 -> ClaudeException(ClaudeException.Kind.OVERLOADED, detail.ifEmpty { "sobrecarregado" }, retryMs)
            400, 404, 413, 422 -> ClaudeException(ClaudeException.Kind.BAD_REQUEST, detail.ifEmpty { "HTTP $code" })
            in 500..599 -> ClaudeException(ClaudeException.Kind.SERVER, detail.ifEmpty { "HTTP $code" }, retryMs)
            else -> ClaudeException(ClaudeException.Kind.UNKNOWN, detail.ifEmpty { "HTTP $code" })
        }
    }

    private fun parse(raw: String): ClaudeReply {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw ClaudeException(ClaudeException.Kind.UNKNOWN, "resposta inválida do servidor")
        }
        val content = root.optJSONArray("content") ?: JSONArray()
        val clean = JSONArray()
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()

        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> {
                    val t = block.optString("text")
                    if (t.isNotEmpty()) {
                        if (text.isNotEmpty()) text.append(' ')
                        text.append(t)
                        clean.put(JSONObject().put("type", "text").put("text", t))
                    }
                }
                "tool_use" -> {
                    val id = block.optString("id")
                    val name = block.optString("name")
                    val input = block.optJSONObject("input") ?: JSONObject()
                    calls += ToolCall(id, name, input)
                    clean.put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", id)
                            .put("name", name)
                            .put("input", input)
                    )
                }
            }
        }
        return ClaudeReply(text.toString().trim(), calls, root.optString("stop_reason").ifEmpty { null }, clean)
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val MAX_RETRIES = 3
    }
}
