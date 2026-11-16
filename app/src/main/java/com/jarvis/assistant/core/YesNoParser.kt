package com.jarvis.assistant.core

/** Interpreta respostas faladas de confirmação ("sim", "pode", "não", "cancela"...). */
object YesNoParser {

    enum class Answer { YES, NO, UNKNOWN }

    private val yes = setOf(
        "sim", "confirmo", "confirmar", "confirma", "pode", "claro", "ok", "okay", "manda", "mande",
        "isso", "positivo", "autorizo", "yes", "faz", "faca"
    )
    private val no = setOf(
        "nao", "cancela", "cancelar", "cancele", "negativo", "para", "pare", "deixa", "esquece",
        "nunca", "no", "nem"
    )

    /** Em caso de dúvida (ou de resposta contraditória) o resultado é seguro: NO ou UNKNOWN, nunca YES. */
    fun parse(text: String): Answer {
        val words = TextUtils.normalize(text).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return Answer.UNKNOWN
        if (words.any { it in no }) return Answer.NO
        if (words.any { it in yes }) return Answer.YES
        return Answer.UNKNOWN
    }
}
