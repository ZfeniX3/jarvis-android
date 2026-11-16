package com.jarvis.assistant.tools

import android.content.Context
import org.json.JSONArray

/** Catálogo de ferramentas expostas ao Claude. */
class ToolRegistry(context: Context) {

    private val tools: List<Tool> =
        DeviceTools.create(context.applicationContext) + ScreenTools.create(context.applicationContext)

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    fun find(name: String): Tool? = byName[name]

    fun apiJson(): JSONArray = JSONArray().apply { tools.forEach { put(it.toApiJson()) } }
}
