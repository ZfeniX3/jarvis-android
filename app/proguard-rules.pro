# Regras de ofuscação (usadas apenas se isMinifyEnabled = true no build de release).
# O app usa apenas APIs do framework Android e org.json, então nenhuma regra extra é necessária.
-keep class com.jarvis.assistant.accessibility.JarvisAccessibilityService { *; }
