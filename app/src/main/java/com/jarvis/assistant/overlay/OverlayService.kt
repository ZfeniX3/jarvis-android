package com.jarvis.assistant.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.assistant.AssistantController
import com.jarvis.assistant.assistant.Phase
import com.jarvis.assistant.assistant.UiState
import com.jarvis.assistant.security.SettingsStore
import com.jarvis.assistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Serviço em primeiro plano (tipo microfone) que mantém a bolinha flutuante e o painel de resposta.
 * Só existe enquanto o usuário o mantém iniciado; a notificação tem o botão "Parar".
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var settings: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var assistant: AssistantController? = null
    private var orb: OrbView? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelShown = false
    private val hidePanelRunnable = Runnable { removePanel() }
    private var snapAnimator: ValueAnimator? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = SettingsStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
        } catch (e: Exception) {
            // Sem permissão de microfone ou início não permitido pelo sistema.
            Toast.makeText(this, "Não foi possível iniciar o Jarvis: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Conceda \"Exibir sobre outros apps\" no app Jarvis.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (orb == null) setUpOverlay()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        controller = null
        handler.removeCallbacksAndMessages(null)
        snapAnimator?.cancel()
        removePanel()
        orb?.let { runCatching { wm.removeView(it) } }
        orb = null
        assistant?.release()
        assistant = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notificação

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    // ------------------------------------------------------------------ bolinha

    private fun screenBounds() = wm.currentWindowMetrics.bounds

    private fun setUpOverlay() {
        val controller = AssistantController(applicationContext, scope)
        assistant = controller
        OverlayService.controller = controller
        running = true

        val view = OrbView(this)
        val size = dp(OrbView.SIZE_DP)
        val b = screenBounds()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (settings.bubbleX >= 0) settings.bubbleX else b.width() - size
            y = if (settings.bubbleY >= 0) settings.bubbleY else b.height() / 3
        }
        orbParams = params
        clampOrb()

        view.onTap = { controller.onOrbTapped() }
        view.onLongPress = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        view.onDrag = { dx, dy ->
            snapAnimator?.cancel()
            params.x += dx
            params.y += dy
            clampOrb()
            updateOrbLayout()
            if (panelShown) showPanel()
        }
        view.onDragEnd = { snapToEdge() }

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível exibir a bolinha: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        orb = view

        scope.launch { controller.ui.collect { render(it) } }
        scope.launch { controller.micLevel.collect { orb?.level = it } }
    }

    private fun clampOrb() {
        val p = orbParams ?: return
        val b = screenBounds()
        val size = dp(OrbView.SIZE_DP)
        p.x = p.x.coerceIn(0, (b.width() - size).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (b.height() - size).coerceAtLeast(0))
    }

    private fun updateOrbLayout() {
        val v = orb ?: return
        val p = orbParams ?: return
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun snapToEdge() {
        val p = orbParams ?: return
        val b = screenBounds()
        val size = dp(OrbView.SIZE_DP)
        val targetX = if (p.x + size / 2 < b.width() / 2) 0 else b.width() - size
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(p.x, targetX).apply {
            duration = 180
            addUpdateListener {
                p.x = it.animatedValue as Int
                updateOrbLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    settings.bubbleX = p.x
                    settings.bubbleY = p.y
                    if (panelShown) showPanel()
                }
            })
            start()
        }
    }

    // ------------------------------------------------------------------ painel de resposta

    private fun render(state: UiState) {
        orb?.phase = state.phase

        val hasText = state.reply.isNotBlank() || state.transcript.isNotBlank() || state.confirmation != null
        val visible = state.phase != Phase.IDLE || hasText
        handler.removeCallbacks(hidePanelRunnable)
        if (!visible) {
            removePanel()
            return
        }
        showPanel()

        val v = panel ?: return
        v.findViewById<TextView>(R.id.panel_status).text = when (state.phase) {
            Phase.LISTENING -> "Ouvindo…"
            Phase.THINKING -> "Pensando…"
            Phase.ACTING -> "Executando…"
            Phase.SPEAKING -> "Falando"
            Phase.CONFIRMING -> "Confirmação necessária"
            Phase.ERROR -> "Erro"
            Phase.IDLE -> "Jarvis"
        }
        v.findViewById<TextView>(R.id.panel_transcript).apply {
            visibility = if (state.transcript.isBlank()) View.GONE else View.VISIBLE
            text = "“${state.transcript}”"
        }
        v.findViewById<TextView>(R.id.panel_reply).apply {
            visibility = if (state.reply.isBlank()) View.GONE else View.VISIBLE
            text = state.reply
        }
        v.findViewById<View>(R.id.panel_confirm_group).visibility =
            if (state.confirmation == null) View.GONE else View.VISIBLE
        v.findViewById<TextView>(R.id.panel_confirm_text).text = state.confirmation.orEmpty()

        // Some sozinho depois de um tempo proporcional ao texto, exceto se aguarda confirmação.
        if (state.phase == Phase.IDLE && state.confirmation == null) {
            handler.postDelayed(hidePanelRunnable, (6000L + state.reply.length * 45L).coerceAtMost(20_000L))
        }
    }

    private fun showPanel() {
        val orbP = orbParams ?: return
        val b = screenBounds()
        val width = min(b.width() - dp(32), dp(380))
        val size = dp(OrbView.SIZE_DP)
        val orbInTopHalf = orbP.y + size / 2 < b.height() / 2

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (orbInTopHalf) {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = orbP.y + size + dp(8)
            } else {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (b.height() - orbP.y) + dp(8)
            }
        }

        var v = panel
        if (v == null) {
            v = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
            v.findViewById<Button>(R.id.panel_btn_confirm).setOnClickListener { assistant?.resolveConfirmation(true) }
            v.findViewById<Button>(R.id.panel_btn_cancel).setOnClickListener { assistant?.resolveConfirmation(false) }
            panel = v
        }
        try {
            if (panelShown) wm.updateViewLayout(v, params) else wm.addView(v, params)
            panelShown = true
        } catch (_: Exception) {
            panelShown = false
        }
    }

    private fun removePanel() {
        handler.removeCallbacks(hidePanelRunnable)
        val v = panel ?: return
        if (panelShown) runCatching { wm.removeView(v) }
        panelShown = false
        panel = null
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_active"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.jarvis.assistant.action.STOP"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var controller: AssistantController? = null

        /** Envia um comando digitado ao assistente em execução. Retorna false se o Jarvis não está iniciado. */
        fun submitText(text: String): Boolean {
            val c = controller ?: return false
            c.submitText(text)
            return true
        }

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, OverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
            running = false
        }
    }
}
