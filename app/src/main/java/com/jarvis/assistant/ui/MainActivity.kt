package com.jarvis.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.jarvis.assistant.R
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.ai.ClaudeClient
import com.jarvis.assistant.ai.ClaudeException
import com.jarvis.assistant.overlay.OverlayService
import com.jarvis.assistant.security.SecureKeyStore
import com.jarvis.assistant.security.SettingsStore
import kotlinx.coroutines.launch

/** Tela única: permissões, chave da API, modelo e iniciar/parar a bolinha. */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private val refreshers = mutableListOf<() -> Unit>()

    private lateinit var apiKeyStatus: TextView
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var btnStartStop: MaterialButton
    private lateinit var startHint: TextView
    private lateinit var modelHint: TextView
    private var micAskedBefore = false

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Negado de forma permanente: só dá para conceder nas configurações do app.
            Toast.makeText(this, "Conceda o microfone nas configurações do app.", Toast.LENGTH_LONG).show()
            openAppDetails()
        }
        refreshAll()
    }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAll() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = SettingsStore(this)

        val root = findViewById<View>(R.id.root_scroll)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        apiKeyStatus = findViewById(R.id.api_key_status)
        apiKeyInput = findViewById(R.id.api_key_input)
        btnStartStop = findViewById(R.id.btn_start_stop)
        startHint = findViewById(R.id.start_hint)
        modelHint = findViewById(R.id.model_hint)

        buildPermissionRows()
        setUpApiKey()
        setUpModel()
        setUpSpeakSwitch()
        setUpStartStop()
        setUpTextTest()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        refreshers.forEach { it() }
        refreshApiKeyStatus()
        refreshStartButton()
    }

    // ------------------------------------------------------------------ permissões

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun buildPermissionRows() {
        val container = findViewById<LinearLayout>(R.id.permissions_container)

        addRow(container, R.string.perm_mic_title, R.string.perm_mic_desc, ::hasMic, R.string.action_grant) {
            micAskedBefore = true
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        addRow(
            container, R.string.perm_overlay_title, R.string.perm_overlay_desc,
            { Settings.canDrawOverlays(this) }, R.string.action_grant
        ) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        addRow(
            container, R.string.perm_accessibility_title, R.string.perm_accessibility_desc,
            { JarvisAccessibilityService.isEnabled(this) }, R.string.action_grant
        ) { showAccessibilityExplanation() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addRow(
                container, R.string.perm_notifications_title, R.string.perm_notifications_desc,
                ::hasNotifications, R.string.action_grant
            ) { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    private fun addRow(
        container: LinearLayout,
        title: Int,
        desc: Int,
        isGranted: () -> Boolean,
        actionLabel: Int,
        onAction: () -> Unit
    ) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_permission, container, false)
        row.findViewById<TextView>(R.id.perm_title).setText(title)
        row.findViewById<TextView>(R.id.perm_desc).setText(desc)
        val status = row.findViewById<TextView>(R.id.perm_status)
        val action = row.findViewById<MaterialButton>(R.id.perm_action)
        action.setOnClickListener { onAction() }
        container.addView(row)

        refreshers += {
            val ok = isGranted()
            status.setText(if (ok) R.string.status_granted else R.string.status_missing)
            status.setTextColor(ContextCompat.getColor(this, if (ok) R.color.jarvis_green else R.color.jarvis_amber))
            action.setText(if (ok) R.string.action_manage else actionLabel)
        }
    }

    /** O Android exige que o usuário ative o serviço manualmente; explicamos antes de abrir a tela. */
    private fun showAccessibilityExplanation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.accessibility_dialog_title)
            .setMessage(R.string.accessibility_dialog_message)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton(R.string.action_app_info) { _, _ -> openAppDetails() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    // ------------------------------------------------------------------ chave da API

    private fun setUpApiKey() {
        val save = findViewById<MaterialButton>(R.id.btn_save_key)
        val test = findViewById<MaterialButton>(R.id.btn_test_key)
        val remove = findViewById<MaterialButton>(R.id.btn_remove_key)

        save.setOnClickListener {
            val key = apiKeyInput.text?.toString().orEmpty().trim()
            when {
                key.length < 20 || key.any { it.isWhitespace() } ->
                    toast("A chave parece inválida. Cole a chave completa, sem espaços.")
                SecureKeyStore.saveApiKey(this, key) -> {
                    apiKeyInput.setText("")
                    toast("Chave salva com segurança neste aparelho.")
                    refreshAll()
                }
                else -> toast("Não consegui salvar a chave de forma segura neste aparelho.")
            }
        }

        remove.setOnClickListener {
            SecureKeyStore.clearApiKey(this)
            toast("Chave removida.")
            refreshAll()
        }

        test.setOnClickListener {
            if (!SecureKeyStore.hasApiKey(this)) {
                toast("Salve a chave primeiro.")
                return@setOnClickListener
            }
            test.isEnabled = false
            lifecycleScope.launch {
                val client = ClaudeClient({ SecureKeyStore.loadApiKey(this@MainActivity) }, { settings.model })
                try {
                    client.ping()
                    toast("Conexão com o Claude funcionando.")
                } catch (e: ClaudeException) {
                    toast(e.userMessage())
                } catch (e: Exception) {
                    toast("Falha no teste: ${e.message}")
                } finally {
                    test.isEnabled = true
                }
            }
        }
    }

    private fun refreshApiKeyStatus() {
        apiKeyStatus.text = if (SecureKeyStore.hasApiKey(this)) {
            "Chave salva: ${SecureKeyStore.maskedKey(this)}"
        } else {
            "Nenhuma chave salva."
        }
    }

    // ------------------------------------------------------------------ modelo e voz

    private fun setUpModel() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.model_toggle)
        fun hint(id: Int) = modelHint.setText(
            when (id) {
                R.id.btn_model_fast -> R.string.model_hint_fast
                R.id.btn_model_advanced -> R.string.model_hint_advanced
                else -> R.string.model_hint_balanced
            }
        )

        val initial = when (settings.model) {
            SettingsStore.MODEL_FAST -> R.id.btn_model_fast
            SettingsStore.MODEL_ADVANCED -> R.id.btn_model_advanced
            else -> R.id.btn_model_balanced
        }
        group.check(initial)
        hint(initial)

        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.model = when (checkedId) {
                R.id.btn_model_fast -> SettingsStore.MODEL_FAST
                R.id.btn_model_advanced -> SettingsStore.MODEL_ADVANCED
                else -> SettingsStore.MODEL_BALANCED
            }
            hint(checkedId)
        }
    }

    private fun setUpSpeakSwitch() {
        val sw = findViewById<MaterialSwitch>(R.id.switch_speak)
        sw.isChecked = settings.speakResponses
        sw.setOnCheckedChangeListener { _, checked -> settings.speakResponses = checked }
    }

    // ------------------------------------------------------------------ iniciar / parar

    private fun missingForStart(): List<String> = buildList {
        if (!hasMic()) add("microfone")
        if (!Settings.canDrawOverlays(this@MainActivity)) add("exibir sobre outros apps")
        if (!SecureKeyStore.hasApiKey(this@MainActivity)) add("chave da API")
    }

    private fun setUpStartStop() {
        btnStartStop.setOnClickListener {
            if (OverlayService.running) {
                OverlayService.stop(this)
                refreshStartButton(running = false)
                return@setOnClickListener
            }
            val missing = missingForStart()
            if (missing.isNotEmpty()) {
                toast("Falta: ${missing.joinToString(", ")}.")
                return@setOnClickListener
            }
            OverlayService.start(this)
            refreshStartButton(running = true)
        }
    }

    private fun refreshStartButton(running: Boolean = OverlayService.running) {
        btnStartStop.setText(if (running) R.string.action_stop else R.string.action_start)
        val missing = missingForStart()
        startHint.text = when {
            running -> ""
            missing.isNotEmpty() -> "Falta: ${missing.joinToString(", ")}."
            !JarvisAccessibilityService.isEnabled(this) ->
                "Sem Acessibilidade o Jarvis responde e abre apps, mas não toca nem digita em outros apps."
            else -> ""
        }
    }

    // ------------------------------------------------------------------ teste por texto

    private fun setUpTextTest() {
        val input = findViewById<TextInputEditText>(R.id.test_input)
        fun send() {
            val text = input.text?.toString().orEmpty().trim()
            if (text.isEmpty()) return
            if (OverlayService.submitText(text)) {
                input.setText("")
                toast("Comando enviado. A resposta aparece na bolinha.")
            } else {
                toast("Toque em \"Iniciar Jarvis\" primeiro.")
            }
        }
        findViewById<MaterialButton>(R.id.btn_send_test).setOnClickListener { send() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else false
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
