# Jarvis – assistente pessoal de voz para Android

Bolinha flutuante + voz em português do Brasil + controle do celular via Acessibilidade + IA (API do Claude).

**Versões usadas:** Android Gradle Plugin 8.7.3 · Gradle 8.9 (wrapper) · Kotlin 2.0.21 · JDK 17 · compileSdk/targetSdk 35 · minSdk 30 (Android 11).

## Compilar e instalar

**Pré-requisitos:** Android Studio (versão recente, Ladybug ou mais nova) e internet na primeira sincronização.

1. Descompacte o projeto e abra a pasta `jarvis-android` no Android Studio (*File > Open*).
2. Aguarde o *Gradle Sync*. Se pedir, aceite instalar o **Android SDK Platform 35**.
3. No celular: ative *Opções do desenvolvedor > Depuração USB* e conecte ao computador.
4. Clique em ▶ **Run** (escolhendo o seu aparelho). Isso compila e instala.

**Só gerar o APK:** *Build > Build Bundle(s) / APK(s) > Build APK(s)*.
O arquivo fica em `app/build/outputs/apk/debug/app-debug.apk`. Instale com:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ou copie o APK para o celular e abra-o (permita "instalar apps desconhecidos" para o app usado).

Por linha de comando (precisa de `ANDROID_HOME` ou `local.properties` com `sdk.dir=...`):
`./gradlew :app:assembleDebug` (Windows: `gradlew.bat :app:assembleDebug`) e `./gradlew :app:testDebugUnitTest`.

## Primeiro uso

1. Abra o app **Jarvis** e conceda, na ordem: **Microfone**, **Exibir sobre outros apps** e (opcional, mas necessário para controlar outros apps) **Acessibilidade**.
2. Cole a **chave da API do Claude** (crie em console.anthropic.com) e toque em **Salvar** e **Testar**.
3. Toque em **Iniciar Jarvis**. A bolinha aparece sobre os outros apps.
4. **Toque na bolinha e fale.** Toque de novo para interromper. Arraste para mover; segure para abrir o app.

**Acessibilidade em apps instalados por APK (Android 13+):** se a opção estiver cinza ("Configuração restrita"), abra *Informações do app > ⋮ > Permitir configurações restritas* e volte para ativar. O app mostra esse passo na própria tela de permissão.

Exemplos: "abre o WhatsApp", "liga a lanterna", "volume da mídia em 30%", "alarme às 6 e meia", "timer de 10 minutos", "que horas são?", "abre o Chrome e pesquisa receita de bolo", "role a tela para baixo", "volta".

## O que o assistente faz

| Área | Como |
|---|---|
| Voz | `SpeechRecognizer` (pt-BR) e `TextToSpeech` (pt-BR), sem escuta contínua: só ouve depois do toque |
| Apps e sistema | abrir apps, lanterna, volume, alarme, timer, links, pesquisa, telas de ajustes, rascunho de SMS, discador |
| Tela (Acessibilidade) | ler textos visíveis, tocar, digitar, rolar, voltar/início/recentes/notificações |
| IA | Claude decide quais **ferramentas** chamar; o app valida e executa |

## Segurança (aplicada no código, não pelo modelo)

- **Confirmação obrigatória** (botões ou "sim/não" por voz) antes de tocar em elementos como Enviar, Excluir, Pagar, Compartilhar, Sair etc. Sem "sim" válido para aquele mesmo elemento, a ação não executa. Sem resposta em 30 s = não.
- A IA só chama ferramentas de uma **lista fixa**, com parâmetros tipados. Nenhum texto/código/shell do modelo é executado.
- O texto lido da tela é tratado como **dado não confiável** (proteção contra instruções falsas escritas na tela).
- O serviço **recusa** operar em apps de banco/carteira/senhas/autenticação, no instalador e nas telas de permissão do sistema, e nunca lê nem digita em campos de senha. É uma proteção de melhor esforço, baseada no nome do pacote.
- SMS e ligação só **abrem a tela**; quem envia/liga é você. O app diz isso explicitamente, sem afirmar que enviou.
- Permissões: `INTERNET` (API), `RECORD_AUDIO` (voz), `SYSTEM_ALERT_WINDOW` (bolinha), `FOREGROUND_SERVICE(_MICROPHONE)` e `POST_NOTIFICATIONS` (serviço ativo com aviso e botão Parar), `SET_ALARM` (alarme/timer), `MODIFY_AUDIO_SETTINGS` (volume). Nada é concedido silenciosamente.
- **Privacidade:** só o texto do seu comando e, quando necessário, o texto da tela vão para a API da Anthropic. O histórico guardado entre comandos não inclui conteúdo de tela.

## Chave da API

Fica cifrada (AES-256-GCM) com chave no **Android Keystore**, nunca em código, recursos, logs ou `BuildConfig`. Importante: o app chama a API **diretamente do celular**; isso serve para uso pessoal/protótipo. Para distribuir a outras pessoas, use um **backend/proxy** que guarde a chave no servidor, com autenticação e limite de uso.

Os IDs dos modelos estão em `security/SettingsStore.kt` (`MODEL_FAST/BALANCED/ADVANCED`); ajuste lá se a Anthropic mudar os nomes.

## Limites do Android

- Não há "Ei Jarvis" em segundo plano (consome bateria e o Android restringe o microfone); a ativação é por toque.
- O Android não deixa apps ligarem/desligarem Wi-Fi/Bluetooth sozinhos (só abrir a tela) nem criarem alarmes com confirmação de volta.
- Alguns apps bloqueiam leitura de tela (`FLAG_SECURE`) ou não expõem seus elementos; nesses casos o assistente avisa.
- O reconhecedor de voz do Google pode exigir internet.

## Estrutura

```
app/src/main/java/com/jarvis/assistant/
  core/           lógica pura e testada (texto, política de ações sensíveis, sim/não, busca de apps)
  security/       SecureKeyStore (Keystore), SettingsStore
  ai/             ClaudeClient (Messages API, timeout, retry, cancelamento)
  tools/          contrato Tool + DeviceTools (nativas) + ScreenTools (acessibilidade)
  accessibility/  JarvisAccessibilityService
  voice/          SpeechInput, Speaker
  assistant/      AssistantController (voz -> Claude -> ferramentas -> fala)
  overlay/        OrbView (bolinha animada), OverlayService (janela flutuante + painel)
  ui/             MainActivity (permissões, chave, modelo, iniciar/parar)
```
