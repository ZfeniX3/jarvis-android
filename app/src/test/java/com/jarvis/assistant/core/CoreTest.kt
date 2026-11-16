package com.jarvis.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {

    // ---------- TextUtils ----------

    @Test
    fun normalize_removesAccentsAndPunctuation() {
        assertEquals("configuracoes", TextUtils.normalize("Configurações!"))
        assertEquals("sao paulo sp", TextUtils.normalize("  São   Paulo, SP "))
    }

    @Test
    fun forSpeech_stripsMarkdownAndEmoji() {
        val out = TextUtils.forSpeech("**Pronto!** Abri o `WhatsApp` 😀 [veja](http://x.com)\n- item um")
        assertEquals("Pronto! Abri o WhatsApp veja item um", out)
    }

    @Test
    fun chunk_respectsMaxLengthAndKeepsAllText() {
        val sentence = "Esta é uma frase de teste. "
        val text = sentence.repeat(60).trim()
        val parts = TextUtils.chunk(text, 200)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 200 })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test
    fun chunk_shortTextIsReturnedAsIs() {
        assertEquals(listOf("oi"), TextUtils.chunk("oi", 100))
    }

    @Test
    fun truncate_addsEllipsis() {
        assertEquals("abc", TextUtils.truncate("abc", 5))
        assertEquals("abc…", TextUtils.truncate("abcdefgh", 4))
    }

    // ---------- SensitiveActionPolicy ----------

    @Test
    fun sensitive_detectsSendDeletePay() {
        assertTrue(SensitiveActionPolicy.isSensitiveLabel("Enviar"))
        assertTrue(SensitiveActionPolicy.isSensitiveLabel("Enviar mensagem"))
        assertTrue(SensitiveActionPolicy.isSensitiveLabel("Excluir conversa"))
        assertTrue(SensitiveActionPolicy.isSensitiveLabel("Pagar com Pix"))
        assertTrue(SensitiveActionPolicy.isSensitiveLabel("Send"))
        assertTrue(SensitiveActionPolicy.isSensitiveLabel("Sign out"))
        assertTrue(SensitiveActionPolicy.isSensitiveLabel("Finalizar compra"))
    }

    @Test
    fun sensitive_ignoresHarmlessLabels() {
        assertFalse(SensitiveActionPolicy.isSensitiveLabel("Pesquisar"))
        assertFalse(SensitiveActionPolicy.isSensitiveLabel("Conversas"))
        assertFalse(SensitiveActionPolicy.isSensitiveLabel("Voltar"))
        assertFalse(SensitiveActionPolicy.isSensitiveLabel(""))
    }

    @Test
    fun sensitive_matchesWholeWordsOnly() {
        // "enviados" não deve disparar por conter "enviar"; palavras inteiras apenas
        assertFalse(SensitiveActionPolicy.isSensitiveLabel("Itens recebidos"))
        assertTrue(SensitiveActionPolicy.anySensitive(listOf("Início", "Apagar tudo")))
    }

    // ---------- YesNoParser ----------

    @Test
    fun yesNo_parsesCommonAnswers() {
        assertEquals(YesNoParser.Answer.YES, YesNoParser.parse("Sim"))
        assertEquals(YesNoParser.Answer.YES, YesNoParser.parse("pode mandar"))
        assertEquals(YesNoParser.Answer.NO, YesNoParser.parse("Não"))
        assertEquals(YesNoParser.Answer.NO, YesNoParser.parse("cancela isso"))
    }

    @Test
    fun yesNo_contradictionIsNeverYes() {
        assertEquals(YesNoParser.Answer.NO, YesNoParser.parse("não pode"))
        assertEquals(YesNoParser.Answer.NO, YesNoParser.parse("sim, não"))
        assertEquals(YesNoParser.Answer.UNKNOWN, YesNoParser.parse("talvez"))
        assertEquals(YesNoParser.Answer.UNKNOWN, YesNoParser.parse(""))
    }

    // ---------- AppMatcher ----------

    private val apps = listOf(
        AppEntry("WhatsApp", "com.whatsapp"),
        AppEntry("WhatsApp Business", "com.whatsapp.w4b"),
        AppEntry("Instagram", "com.instagram.android"),
        AppEntry("Configurações", "com.android.settings"),
        AppEntry("Chrome", "com.android.chrome"),
        AppEntry("Google Maps", "com.google.android.apps.maps"),
        AppEntry("Calculadora", "com.google.android.calculator")
    )

    @Test
    fun appMatcher_findsExactAndFuzzyNames() {
        assertEquals("com.whatsapp", AppMatcher.best("whatsapp", apps)?.packageName)
        assertEquals("com.whatsapp", AppMatcher.best("abrir o WhatsApp", apps)?.packageName)
        assertEquals("com.instagram.android", AppMatcher.best("instagram", apps)?.packageName)
        assertEquals("com.android.settings", AppMatcher.best("configuracoes", apps)?.packageName)
        assertEquals("com.google.android.apps.maps", AppMatcher.best("maps", apps)?.packageName)
    }

    @Test
    fun appMatcher_usesAliases() {
        assertEquals("com.whatsapp", AppMatcher.best("zap", apps)?.packageName)
        assertEquals("com.instagram.android", AppMatcher.best("insta", apps)?.packageName)
        assertEquals("com.android.chrome", AppMatcher.best("navegador", apps)?.packageName)
        assertEquals("com.android.settings", AppMatcher.best("ajustes", apps)?.packageName)
    }

    @Test
    fun appMatcher_prefersExactOverLongerName() {
        val ranked = AppMatcher.rank("whatsapp", apps)
        assertEquals("com.whatsapp", ranked.first().first.packageName)
        assertTrue(ranked.size >= 2)
    }

    @Test
    fun appMatcher_returnsNullWhenNothingMatches() {
        assertNull(AppMatcher.best("aplicativo inexistente xyz", apps))
        assertNull(AppMatcher.best("", apps))
    }
}
