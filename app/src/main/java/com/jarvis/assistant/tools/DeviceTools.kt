package com.jarvis.assistant.tools

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import com.jarvis.assistant.core.AppMatcher
import com.jarvis.assistant.device.AppLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Ferramentas nativas: só usam intents e APIs oficiais do Android. */
object DeviceTools {

    fun create(context: Context): List<Tool> = listOf(
        OpenApp(context),
        DateTimeTool(),
        BatteryTool(context),
        Flashlight(context),
        Volume(context),
        SetAlarm(context),
        SetTimer(context),
        OpenUrl(context),
        WebSearch(context),
        OpenSettings(context),
        ComposeSms(context),
        Dial(context)
    )

    /** Inicia uma Activity. Devolve uma mensagem de erro, ou null se o Android aceitou o pedido. */
    private fun start(context: Context, intent: Intent): String? = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: ActivityNotFoundException) {
        "Nenhum app do aparelho consegue tratar este pedido."
    } catch (e: SecurityException) {
        "O Android bloqueou a ação: ${e.message}"
    }

    private val phoneRegex = Regex("^[+0-9 ()\\-]{3,20}$")

    // ---------------------------------------------------------------- apps

    private class OpenApp(private val context: Context) : Tool(
        "open_app",
        "Abre um app instalado pelo nome (ex.: WhatsApp, Chrome, Configurações, Câmera).",
        listOf(Prop("name", "string", "Nome do app como o usuário falou"))
    ) {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            val name = input.optString("name").trim()
            if (name.isEmpty()) return ToolResult.error("Informe o nome do app.")
            val apps = withContext(Dispatchers.IO) { AppLauncher.list(context) }
            val best = AppMatcher.best(name, apps)
                ?: return ToolResult.error("Nenhum app chamado \"$name\" foi encontrado entre os instalados.")
            val intent = context.packageManager.getLaunchIntentForPackage(best.packageName)
                ?: return ToolResult.error("O app ${best.label} não pode ser aberto.")
            DeviceTools.start(context, intent)?.let { return ToolResult.error(it) }
            return ToolResult.ok(
                "Pedi ao Android para abrir ${best.label}. Se precisar agir dentro dele, use read_screen para ver se a tela abriu."
            )
        }
    }

    // ---------------------------------------------------------------- informações

    private class DateTimeTool : Tool("get_datetime", "Informa a data e a hora atuais do aparelho.") {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            val fmt = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", Locale("pt", "BR"))
            return ToolResult.ok(ZonedDateTime.now().format(fmt))
        }
    }

    private class BatteryTool(private val context: Context) :
        Tool("get_battery", "Informa o nível de bateria e se está carregando.") {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val state = if (bm.isCharging) "carregando" else "sem carregar"
            return ToolResult.ok("Bateria em $level%, $state.")
        }
    }

    // ---------------------------------------------------------------- hardware

    private class Flashlight(private val context: Context) : Tool(
        "set_flashlight",
        "Liga ou desliga a lanterna.",
        listOf(Prop("on", "boolean", "true para ligar, false para desligar"))
    ) {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            val on = input.optBoolean("on", true)
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return try {
                val withFlash = cm.cameraIdList.filter {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                val id = withFlash.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK
                } ?: withFlash.firstOrNull()
                    ?: return ToolResult.error("Este aparelho não tem lanterna.")
                cm.setTorchMode(id, on)
                ToolResult.ok(if (on) "Lanterna ligada." else "Lanterna desligada.")
            } catch (_: CameraAccessException) {
                ToolResult.error("Não consegui acessar a lanterna; a câmera pode estar em uso por outro app.")
            } catch (e: IllegalArgumentException) {
                ToolResult.error("Não consegui acessar a lanterna: ${e.message}")
            }
        }
    }

    private class Volume(private val context: Context) : Tool(
        "set_volume",
        "Ajusta um volume. Use level (0 a 100) para um valor exato, ou change para subir/descer um nível.",
        listOf(
            Prop("stream", "string", "Qual volume", true, listOf("media", "ring", "alarm")),
            Prop("level", "integer", "Percentual de 0 a 100 (0 = mudo)", false),
            Prop("change", "string", "Subir ou descer um nível", false, listOf("up", "down"))
        )
    ) {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamName = input.optString("stream", "media")
            val type = when (streamName) {
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_MUSIC
            }
            val max = am.getStreamMaxVolume(type).coerceAtLeast(1)
            try {
                if (input.has("level")) {
                    val pct = input.optInt("level").coerceIn(0, 100)
                    am.setStreamVolume(type, (pct * max / 100.0).roundToInt(), AudioManager.FLAG_SHOW_UI)
                } else {
                    when (input.optString("change")) {
                        "up" -> am.adjustStreamVolume(type, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                        "down" -> am.adjustStreamVolume(type, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                        else -> return ToolResult.error("Informe level (0 a 100) ou change (up/down).")
                    }
                }
            } catch (_: SecurityException) {
                return ToolResult.error("O Android não permitiu mudar este volume (talvez o modo Não perturbe esteja ativo).")
            }
            return ToolResult.ok("Volume de $streamName agora em ${am.getStreamVolume(type) * 100 / max}%.")
        }
    }

    // ---------------------------------------------------------------- relógio

    private class SetAlarm(private val context: Context) : Tool(
        "set_alarm",
        "Cria um alarme no app de relógio do aparelho.",
        listOf(
            Prop("hour", "integer", "Hora (0 a 23)"),
            Prop("minute", "integer", "Minuto (0 a 59)"),
            Prop("label", "string", "Nome do alarme", false)
        )
    ) {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            if (!input.has("hour") || !input.has("minute")) return ToolResult.error("Informe hour e minute.")
            val hour = input.optInt("hour", -1)
            val minute = input.optInt("minute", -1)
            if (hour !in 0..23 || minute !in 0..59) return ToolResult.error("Horário inválido.")
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("label"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            DeviceTools.start(context, intent)?.let { return ToolResult.error(it) }
            return ToolResult.ok(
                "Enviei ao app de relógio o pedido de alarme para %02d:%02d. O Android não devolve confirmação; o usuário pode conferir no app de relógio."
                    .format(hour, minute)
            )
        }
    }

    private class SetTimer(private val context: Context) : Tool(
        "set_timer",
        "Inicia um timer (contagem regressiva) no app de relógio.",
        listOf(
            Prop("seconds", "integer", "Duração em segundos (1 a 86400)"),
            Prop("label", "string", "Nome do timer", false)
        )
    ) {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            val seconds = input.optInt("seconds", -1)
            if (seconds !in 1..86_400) return ToolResult.error("Duração inválida (use 1 a 86400 segundos).")
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("label"))
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            DeviceTools.start(context, intent)?.let { return ToolResult.error(it) }
            return ToolResult.ok(
                "Enviei ao app de relógio o pedido de timer de $seconds segundos. O Android não devolve confirmação."
            )
        }
    }

    // ---------------------------------------------------------------- web

    private class OpenUrl(private val context: Context) : Tool(
        "open_url",
        "Abre um link http/https no navegador.",
        listOf(Prop("url", "string", "Endereço completo, começando com https://"))
    ) {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            val uri = Uri.parse(input.optString("url").trim())
            val scheme = uri.scheme?.lowercase()
            if ((scheme != "https" && scheme != "http") || uri.host.isNullOrBlank()) {
                return ToolResult.error("Só abro links http ou https válidos.")
            }
            DeviceTools.start(context, Intent(Intent.ACTION_VIEW, uri))?.let { return ToolResult.error(it) }
            return ToolResult.ok("Pedi ao Android para abrir o link ${uri.host}.")
        }
    }

    private class WebSearch(private val context: Context) : Tool(
        "web_search",
        "Abre uma pesquisa na web no navegador.",
        listOf(Prop("query", "string", "Texto da pesquisa"))
    ) {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            val q = input.optString("query").trim()
            if (q.isEmpty()) return ToolResult.error("Informe o texto da pesquisa.")
            val primary = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
            if (DeviceTools.start(context, primary) != null) {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
                DeviceTools.start(context, fallback)?.let { return ToolResult.error(it) }
            }
            return ToolResult.ok("Abri a pesquisa por \"$q\" no navegador.")
        }
    }

    // ---------------------------------------------------------------- ajustes

    private class OpenSettings(private val context: Context) : Tool(
        "open_settings",
        "Abre uma tela de ajustes do Android. O Android não permite que apps liguem/desliguem Wi-Fi ou Bluetooth sozinhos; esta ferramenta só abre a tela para o usuário.",
        listOf(
            Prop(
                "section", "string", "Qual tela abrir", true,
                listOf("general", "wifi", "bluetooth", "display", "sound", "battery", "apps", "location", "airplane")
            )
        )
    ) {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            val section = input.optString("section", "general")
            val action = when (section) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS
                "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            DeviceTools.start(context, Intent(action))?.let { return ToolResult.error(it) }
            return ToolResult.ok("Abri a tela de ajustes ($section). Nada foi alterado; o usuário precisa mudar a opção.")
        }
    }

    // ---------------------------------------------------------------- comunicação (nunca envia sozinho)

    private class ComposeSms(private val context: Context) : Tool(
        "compose_sms",
        "Abre o app de mensagens com destinatário e texto preenchidos. NÃO envia a mensagem.",
        listOf(
            Prop("number", "string", "Número de telefone do destinatário"),
            Prop("text", "string", "Texto da mensagem", false)
        )
    ) {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            val number = input.optString("number").trim()
            if (!DeviceTools.phoneRegex.matches(number)) return ToolResult.error("Número de telefone inválido.")
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                .putExtra("sms_body", input.optString("text"))
            DeviceTools.start(context, intent)?.let { return ToolResult.error(it) }
            return ToolResult.ok("Abri a tela de mensagem com o texto preenchido. A mensagem NÃO foi enviada; falta tocar em enviar.")
        }
    }

    private class Dial(private val context: Context) : Tool(
        "open_dialer",
        "Abre o discador com o número preenchido. NÃO faz a ligação.",
        listOf(Prop("number", "string", "Número de telefone"))
    ) {
        override suspend fun run(input: JSONObject, approved: Boolean): ToolResult {
            val number = input.optString("number").trim()
            if (!DeviceTools.phoneRegex.matches(number)) return ToolResult.error("Número de telefone inválido.")
            DeviceTools.start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
                ?.let { return ToolResult.error(it) }
            return ToolResult.ok("Abri o discador com o número. A ligação NÃO foi feita; falta tocar em ligar.")
        }
    }
}
