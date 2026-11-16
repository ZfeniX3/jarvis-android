package com.jarvis.assistant.core

import java.text.Normalizer

/** Utilitários de texto puros (sem dependência do Android), cobertos por testes unitários. */
object TextUtils {

    private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALNUM = Regex("[^a-z0-9 ]")
    private val SPACES = Regex("\\s+")

    /** Minúsculas, sem acentos e sem pontuação: "Configurações!" -> "configuracoes". */
    fun normalize(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return DIACRITICS.replace(decomposed, "")
            .lowercase()
            .replace(NON_ALNUM, " ")
            .replace(SPACES, " ")
            .trim()
    }

    private val CODE_BLOCK = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val MD_LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val BULLET = Regex("(?m)^\\s*[-•*]\\s+")
    private val MD_SYMBOLS = Regex("[*_#>~|]+")
    private val EMOJI = Regex("[\\p{So}\\p{Cs}]")

    /** Remove markdown, emojis e símbolos para que o texto fique natural quando lido em voz alta. */
    fun forSpeech(text: String): String {
        var t = text
        t = CODE_BLOCK.replace(t, " ")
        t = INLINE_CODE.replace(t, "$1")
        t = MD_LINK.replace(t, "$1")
        t = BULLET.replace(t, "")
        t = MD_SYMBOLS.replace(t, " ")
        t = EMOJI.replace(t, "")
        return t.replace(SPACES, " ").trim()
    }

    /** Divide um texto longo em pedaços de no máximo [maxLen] caracteres, preferindo fins de frase. */
    fun chunk(text: String, maxLen: Int): List<String> {
        require(maxLen >= 20) { "maxLen muito pequeno" }
        if (text.length <= maxLen) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > maxLen) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), maxLen - 1)
            if (cut < maxLen / 2) cut = rest.lastIndexOf(' ', maxLen - 1)
            if (cut <= 0) cut = maxLen - 1
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotBlank() }
    }

    /** Limita o texto a [max] caracteres, acrescentando reticências quando cortar. */
    fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max - 1).trimEnd() + "…"
}
