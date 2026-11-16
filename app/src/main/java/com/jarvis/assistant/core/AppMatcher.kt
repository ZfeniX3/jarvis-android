package com.jarvis.assistant.core

data class AppEntry(val label: String, val packageName: String)

/** Encontra o app instalado que melhor corresponde ao nome falado pelo usuário. */
object AppMatcher {

    const val MIN_SCORE = 35

    private val stopWords = setOf(
        "abrir", "abre", "abra", "o", "a", "os", "as", "app", "aplicativo", "aplicacao",
        "do", "da", "de", "no", "na", "para", "pra", "meu", "minha"
    )

    private val aliases = mapOf(
        "zap" to "whatsapp",
        "zapzap" to "whatsapp",
        "wpp" to "whatsapp",
        "insta" to "instagram",
        "face" to "facebook",
        "tik tok" to "tiktok",
        "navegador" to "chrome",
        "internet" to "chrome",
        "mapa" to "maps",
        "mapas" to "maps",
        "email" to "gmail",
        "e mail" to "gmail",
        "loja de aplicativos" to "play store",
        "loja" to "play store",
        "ajustes" to "configuracoes",
        "config" to "configuracoes",
        "settings" to "configuracoes"
    )

    /** Consulta normalizada, sem palavras de enchimento, mais a variante com apelido. */
    fun variants(query: String): List<String> {
        val cleaned = TextUtils.normalize(query)
            .split(' ')
            .filter { it.isNotEmpty() && it !in stopWords }
            .joinToString(" ")
        if (cleaned.isEmpty()) return emptyList()
        val out = linkedSetOf(cleaned)
        aliases[cleaned]?.let { out += it }
        return out.toList()
    }

    private fun score(q: String, app: AppEntry): Int {
        val label = TextUtils.normalize(app.label)
        if (label.isEmpty() || q.isEmpty()) return 0
        var s = when {
            label == q -> 100
            label.startsWith(q) -> 80
            label.contains(q) -> 60
            q.contains(label) && label.length >= 3 -> 55
            else -> 0
        }
        if (s == 0) {
            val qWords = q.split(' ').filter { it.isNotEmpty() }
            val lWords = label.split(' ').toSet()
            val overlap = qWords.count { it in lWords }
            if (overlap > 0) s = 30 + 40 * overlap / qWords.size
        }
        if (s == 0 && app.packageName.lowercase().contains(q.replace(" ", ""))) s = 35
        return s
    }

    /** Candidatos com pontuação > 0, do melhor para o pior (empate: rótulo mais curto primeiro). */
    fun rank(query: String, apps: List<AppEntry>, limit: Int = 5): List<Pair<AppEntry, Int>> {
        val qs = variants(query)
        if (qs.isEmpty()) return emptyList()
        return apps
            .map { app -> app to (qs.maxOfOrNull { score(it, app) } ?: 0) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }.thenBy { it.first.label.length })
            .take(limit)
    }

    fun best(query: String, apps: List<AppEntry>): AppEntry? =
        rank(query, apps, 1).firstOrNull()?.takeIf { it.second >= MIN_SCORE }?.first
}
