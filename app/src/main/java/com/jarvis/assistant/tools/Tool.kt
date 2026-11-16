package com.jarvis.assistant.tools

import org.json.JSONArray
import org.json.JSONObject

data class ToolResult(val text: String, val isError: Boolean = false) {
    companion object {
        fun ok(text: String) = ToolResult(text)
        fun error(text: String) = ToolResult(text, true)
    }
}

/** Parâmetro de uma ferramenta, descrito em JSON Schema. */
class Prop(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null
)

/**
 * Uma ferramenta que o Claude pode chamar.
 *
 * - [confirmationRequest]: devolve uma pergunta em português se a ação precisar de confirmação do
 *   usuário (ou null se for segura). É avaliada pelo app ANTES de executar.
 * - [run]: executa a ação. O parâmetro [approved] só é true depois que o usuário confirmou de verdade
 *   (botão ou voz). Ferramentas sensíveis recusam executar sem ele (defesa em profundidade).
 */
abstract class Tool(
    val name: String,
    val description: String,
    private val props: List<Prop> = emptyList()
) {
    open suspend fun confirmationRequest(input: JSONObject): String? = null

    abstract suspend fun run(input: JSONObject, approved: Boolean): ToolResult

    private fun schema(): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        for (p in props) {
            val def = JSONObject().put("type", p.type).put("description", p.description)
            p.enumValues?.let { def.put("enum", JSONArray(it)) }
            properties.put(p.name, def)
            if (p.required) required.put(p.name)
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", required)
    }

    fun toApiJson(): JSONObject =
        JSONObject().put("name", name).put("description", description).put("input_schema", schema())
}
