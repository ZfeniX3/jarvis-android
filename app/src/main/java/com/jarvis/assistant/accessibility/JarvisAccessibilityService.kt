package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.core.SensitiveActionPolicy
import com.jarvis.assistant.core.TextUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Um elemento visível da tela, já filtrado e resumido para ser enviado à IA. */
data class ScreenNode(
    val index: Int,
    val text: String,
    val desc: String,
    val viewId: String,
    val type: String,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: Rect
) {
    val label: String get() = text.ifBlank { desc }
}

data class ScreenSnapshot(val packageName: String, val nodes: List<ScreenNode>, val scrollable: Boolean)

sealed interface CaptureResult {
    data class Ok(val snapshot: ScreenSnapshot) : CaptureResult
    data class Fail(val message: String, val transient: Boolean = false) : CaptureResult
}

/** Alvo de um toque já resolvido: guarda o nó vivo e a avaliação de sensibilidade. */
class TapTarget(
    val node: ScreenNode,
    val packageName: String,
    val live: AccessibilityNodeInfo,
    val sensitive: Boolean
) {
    /** Identifica o alvo; se mudar entre a confirmação e a execução, a ação é recusada. */
    val key: String = "$packageName|${node.viewId}|${node.label}|${node.bounds.flattenToString()}"
}

sealed interface TapResolution {
    data class Ok(val target: TapTarget) : TapResolution
    data class Fail(val message: String) : TapResolution
}

/**
 * Serviço de Acessibilidade. O usuário precisa ativá-lo manualmente em Ajustes > Acessibilidade.
 * Só age quando o assistente executa um comando; não observa nem registra eventos.
 * Recusa operar em apps de banco, carteira, senhas, autenticação e permissões do sistema.
 */
class JarvisAccessibilityService : AccessibilityService() {

    @Volatile
    var lastSnapshot: ScreenSnapshot? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intencionalmente vazio: o serviço é acionado por comandos, não reage a eventos.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        lastSnapshot = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        lastSnapshot = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ leitura

    fun capture(): CaptureResult {
        val root = rootInActiveWindow
            ?: return CaptureResult.Fail("Não consegui ler a tela agora (nenhuma janela ativa).", transient = true)
        val pkg = root.packageName?.toString().orEmpty()
        blockedMessage(pkg)?.let { return CaptureResult.Fail(it) }

        val nodes = mutableListOf<ScreenNode>()
        val state = BooleanArray(1) // [0] = há conteúdo rolável
        val visited = IntArray(1)
        collect(root, 0, nodes, state, visited)
        val snap = ScreenSnapshot(pkg, nodes, state[0])
        lastSnapshot = snap
        return CaptureResult.Ok(snap)
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<ScreenNode>,
        state: BooleanArray,
        visited: IntArray
    ) {
        if (node == null || depth > MAX_DEPTH || visited[0] >= MAX_VISITED || out.size >= MAX_NODES) return
        visited[0]++
        if (node.isVisibleToUser) {
            if (node.isScrollable) state[0] = true
            toScreenNode(node, out.size)?.let { out += it }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), depth + 1, out, state, visited)
    }

    private fun toScreenNode(node: AccessibilityNodeInfo, index: Int): ScreenNode? {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val type = typeOf(node.className?.toString())
        if (node.isPassword) {
            // Nunca expõe nada de campos de senha: só avisa que existe.
            return ScreenNode(index, "", "campo de senha (oculto)", "", "campo", false, false, bounds)
        }
        val viewId = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
        val hint = if (node.isEditable) node.hintText?.toString().orEmpty() else ""
        val rawText = if (node.isEditable && node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val text = TextUtils.truncate(rawText.trim(), 100)
        val desc = TextUtils.truncate(
            (node.contentDescription?.toString().orEmpty()).ifBlank { hint }.trim(), 100
        )
        val keep = text.isNotBlank() || desc.isNotBlank() || node.isEditable ||
            (node.isClickable && viewId.isNotBlank())
        if (!keep) return null
        return ScreenNode(index, text, desc, viewId, type, node.isClickable, node.isEditable, bounds)
    }

    private fun typeOf(className: String?): String {
        val n = className?.substringAfterLast('.').orEmpty()
        return when {
            n.contains("EditText") -> "campo"
            n.contains("Button") -> "botão"
            n.contains("Image") -> "imagem"
            n.contains("CheckBox") || n.contains("Switch") || n.contains("Toggle") -> "opção"
            n.contains("TextView") -> "texto"
            else -> "item"
        }
    }

    /** Texto compacto para o modelo; o conteúdo é entregue como DADO, dentro de <tela>. */
    fun render(snapshot: ScreenSnapshot): String {
        val sb = StringBuilder()
        sb.append("<tela app=\"").append(snapshot.packageName).append("\">\n")
        for (n in snapshot.nodes) {
            sb.append('[').append(n.index).append("] ").append(n.type)
            if (n.label.isNotBlank()) sb.append(" \"").append(n.label.replace('\n', ' ')).append('"')
            if (n.viewId.isNotBlank()) sb.append(" id=").append(n.viewId)
            if (n.editable) sb.append(" (editável)") else if (n.clickable) sb.append(" (clicável)")
            sb.append('\n')
            if (sb.length > MAX_OUTPUT_CHARS) {
                sb.append("… (tela truncada)\n")
                break
            }
        }
        if (snapshot.scrollable) sb.append("(há conteúdo rolável)\n")
        sb.append("</tela>")
        return sb.toString()
    }

    // ------------------------------------------------------------------ toque

    fun resolveTap(index: Int?, text: String?): TapResolution {
        val snapshot: ScreenSnapshot
        val entry: ScreenNode
        if (index != null) {
            snapshot = lastSnapshot ?: return TapResolution.Fail("Leia a tela antes (read_screen) para obter os índices.")
            entry = snapshot.nodes.getOrNull(index)
                ?: return TapResolution.Fail("Índice $index não existe na última leitura. Leia a tela de novo.")
        } else if (!text.isNullOrBlank()) {
            snapshot = when (val c = capture()) {
                is CaptureResult.Ok -> c.snapshot
                is CaptureResult.Fail -> return TapResolution.Fail(c.message)
            }
            entry = bestByText(snapshot, text)
                ?: return TapResolution.Fail("Não encontrei na tela um elemento com o texto \"$text\".")
        } else {
            return TapResolution.Fail("Informe index (de read_screen) ou text.")
        }

        if (entry.desc == "campo de senha (oculto)") {
            return TapResolution.Fail("Por segurança não toco em campos de senha.")
        }
        val root = rootInActiveWindow ?: return TapResolution.Fail("Nenhuma janela ativa.")
        val currentPkg = root.packageName?.toString().orEmpty()
        blockedMessage(currentPkg)?.let { return TapResolution.Fail(it) }
        if (currentPkg != snapshot.packageName) {
            return TapResolution.Fail("A tela mudou (agora: $currentPkg). Leia a tela de novo.")
        }
        val live = findLive(root, entry, 0, IntArray(1))
            ?: return TapResolution.Fail("O elemento \"${entry.label}\" não está mais na tela. Leia a tela de novo.")

        val labels = mutableListOf(entry.label, entry.viewId.replace('_', ' '))
        labels += nearbyLabels(clickableAncestor(live) ?: live)
        return TapResolution.Ok(TapTarget(entry, currentPkg, live, SensitiveActionPolicy.anySensitive(labels)))
    }

    private fun bestByText(snapshot: ScreenSnapshot, query: String): ScreenNode? {
        val q = TextUtils.normalize(query)
        if (q.isEmpty()) return null
        fun score(n: ScreenNode): Int {
            val l = TextUtils.normalize(n.label)
            val base = when {
                l.isEmpty() -> 0
                l == q -> 100
                l.startsWith(q) -> 70
                l.contains(q) -> 50
                else -> 0
            }
            return if (base > 0 && n.clickable) base + 10 else base
        }
        return snapshot.nodes.map { it to score(it) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    private fun findLive(node: AccessibilityNodeInfo?, target: ScreenNode, depth: Int, visited: IntArray): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH || visited[0] >= MAX_VISITED) return null
        visited[0]++
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r == target.bounds && node.isVisibleToUser) {
            val t = node.text?.toString().orEmpty().trim()
            val d = node.contentDescription?.toString().orEmpty().trim()
            val vid = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
            val same = (target.text.isNotBlank() && TextUtils.truncate(t, 100) == target.text) ||
                (target.desc.isNotBlank() && TextUtils.truncate(d, 100) == target.desc) ||
                (target.label.isBlank() && vid == target.viewId)
            if (same) return node
        }
        for (i in 0 until node.childCount) findLive(node.getChild(i), target, depth + 1, visited)?.let { return it }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var guard = 0
        while (cur != null && guard++ < 12) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /** Rótulos curtos dentro do elemento clicável (ex.: um botão com ícone + texto "Excluir"). */
    private fun nearbyLabels(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 4 || out.size >= 12) return
            n.text?.toString()?.takeIf { it.isNotBlank() && it.length <= 30 }?.let { out += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() && it.length <= 30 }?.let { out += it }
            n.viewIdResourceName?.substringAfter(":id/")?.let { out += it.replace('_', ' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    suspend fun performTap(target: TapTarget): Boolean {
        val clickable = clickableAncestor(target.live)
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val b = target.node.bounds
        val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        return gesture(path, 60)
    }

    // ------------------------------------------------------------------ digitar

    /** Devolve uma mensagem de erro, ou null se o texto foi colocado no campo. */
    fun typeText(text: String, replace: Boolean): String? {
        val root = rootInActiveWindow ?: return "Nenhuma janela ativa."
        blockedMessage(root.packageName?.toString().orEmpty())?.let { return it }
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: firstEditable(root, 0)
            ?: return "Não encontrei nenhum campo de texto na tela."
        if (field.isPassword) return "Por segurança não digito em campos de senha."
        val existing = if (replace || field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) null
        else "O campo não aceitou o texto."
    }

    private fun firstEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) firstEditable(node.getChild(i), depth + 1)?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ rolagem e navegação

    /** Devolve uma mensagem de erro, ou null se rolou. */
    suspend fun scroll(direction: String): String? {
        val root = rootInActiveWindow ?: return "Nenhuma janela ativa."
        blockedMessage(root.packageName?.toString().orEmpty())?.let { return it }
        val action = when (direction) {
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            else -> return "Direção inválida."
        }
        findScrollable(root, action, 0)?.let { if (it.performAction(action.id)) return null }

        // Alternativa: deslizar o dedo
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val path = Path()
        when (direction) {
            "down" -> { path.moveTo(w / 2, h * 0.7f); path.lineTo(w / 2, h * 0.3f) }
            "up" -> { path.moveTo(w / 2, h * 0.3f); path.lineTo(w / 2, h * 0.7f) }
            "right" -> { path.moveTo(w * 0.7f, h / 2); path.lineTo(w * 0.3f, h / 2) }
            else -> { path.moveTo(w * 0.3f, h / 2); path.lineTo(w * 0.7f, h / 2) }
        }
        return if (gesture(path, 350)) null else "Não consegui rolar a tela."
    }

    private fun findScrollable(
        node: AccessibilityNodeInfo?,
        action: AccessibilityNodeInfo.AccessibilityAction,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > MAX_DEPTH) return null
        if (node.isScrollable && node.actionList.contains(action)) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i), action, depth + 1)?.let { return it }
        return null
    }

    fun global(action: String): Boolean {
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        return performGlobalAction(code)
    }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val dispatched = dispatchGesture(description, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    // ------------------------------------------------------------------ segurança

    private fun blockedMessage(pkg: String): String? =
        if (isBlockedPackage(pkg, packageName)) {
            "Por segurança não leio nem controlo este app ($pkg): ele parece lidar com senhas, dinheiro, autenticação ou permissões do sistema."
        } else null

    companion object {
        private const val MAX_DEPTH = 30
        private const val MAX_VISITED = 1500
        private const val MAX_NODES = 150
        private const val MAX_OUTPUT_CHARS = 6000

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        private val blockedExact = setOf(
            "com.android.systemui", // tela de bloqueio/PIN, notificações, painel rápido
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
        )

        private val blockedKeywords = listOf(
            "bank", "banco", "bradesco", "itau", "santander", "nubank", "nu.production", "intermedium",
            "caixa", "wallet", "paypal", "picpay", "mercadopago", "pay", "authenticator", "password",
            "keepass", "bitwarden", "lastpass", "1password", "dashlane", "securitycenter"
        )

        /** Melhor esforço: bloqueia o app próprio, o sistema e apps cujo pacote indica banco/carteira/senhas. */
        fun isBlockedPackage(pkg: String, ownPackage: String): Boolean {
            if (pkg.isBlank()) return false
            val p = pkg.lowercase()
            if (p == ownPackage.lowercase() || p in blockedExact) return true
            return blockedKeywords.any { p.contains(it) }
        }

        /** O serviço está habilitado nas configurações do sistema? */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, JarvisAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
