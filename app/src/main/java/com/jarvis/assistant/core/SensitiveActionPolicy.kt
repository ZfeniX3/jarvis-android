package com.jarvis.assistant.core

/**
 * Política de segurança: decide se tocar em um elemento com determinado rótulo é uma ação
 * "importante" (enviar, excluir, pagar...) que exige confirmação explícita do usuário.
 *
 * Esta checagem é feita no CÓDIGO do app, não pelo modelo de IA. Assim, mesmo que um texto
 * malicioso na tela tente enganar a IA, a confirmação continua obrigatória.
 */
object SensitiveActionPolicy {

    private val singleWords = setOf(
        // enviar / compartilhar
        "enviar", "envie", "enviado", "mandar", "mande", "send", "encaminhar", "forward",
        "compartilhar", "compartilhe", "share", "publicar", "publique", "publish", "postar", "poste", "post",
        // excluir
        "excluir", "exclua", "apagar", "apague", "deletar", "delete", "remover", "remova", "remove",
        "limpar", "clear", "esvaziar", "desinstalar", "uninstall", "formatar", "reset", "redefinir",
        // dinheiro
        "pagar", "pague", "pay", "comprar", "compre", "buy", "purchase", "checkout", "assinar", "subscribe",
        "transferir", "transfira", "transfer", "pix", "doar", "donate",
        // comunicação / contas / permissões
        "ligar", "ligue", "chamar", "call", "bloquear", "block", "sair", "logout",
        "confirmar", "confirme", "confirm", "aceitar", "accept", "autorizar", "authorize", "permitir", "allow",
        "finalizar"
    )

    private val phrases = listOf(
        "sign out", "log out", "limpar tudo", "clear all", "empty trash", "esvaziar lixeira",
        "factory reset", "redefinir senha", "finalizar compra"
    )

    /** true se o rótulo sugere uma ação importante. */
    fun isSensitiveLabel(label: String): Boolean {
        val words = TextUtils.normalize(label).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        if (words.any { it in singleWords }) return true
        val joined = " " + words.joinToString(" ") + " "
        return phrases.any { joined.contains(" $it ") }
    }

    /** true se qualquer um dos rótulos for sensível. */
    fun anySensitive(labels: Collection<String>): Boolean = labels.any { isSensitiveLabel(it) }
}
