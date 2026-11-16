package com.jarvis.assistant.device

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.core.AppEntry

/** Lista os apps que têm ícone na tela inicial (visíveis graças ao bloco <queries> do manifesto). */
object AppLauncher {

    @Suppress("DEPRECATION")
    fun list(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    /** Nome amigável de um pacote; se não der para resolver, devolve o próprio nome do pacote. */
    fun labelFor(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }
}
