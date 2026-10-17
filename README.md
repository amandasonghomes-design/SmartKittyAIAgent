# 猫猫小助手 · Smart Kitty AI Agent

A cute personal AI assistant for Android, built with Kotlin and Jetpack Compose.
It runs on your phone with **your own OpenAI API key**: no server needed.

## What it can do

- **Chat** by typing or talking, with replies read aloud (pick the voice, pitch and speed)
- **Hands-free voice mode**: tap the wave button and just keep talking
- **Photos**: take or pick a picture and ask about it
- **Pictures**: ask it to draw something, then save it to your gallery
- **Web search** for weather, news and anything current
- **Gmail cleanup**: finds promotions and junk, shows you a list, and only moves
  them to Trash after **you** approve
- **Unsubscribe** from marketing emails, also only after you approve
- **Memory**: remembers facts you tell it, notes about recent chats, and can search
  your past conversations (RAG with embeddings, stored on the phone in SQLite)
- **MLS (optional)**: connect a Flexmls account to ask about listings and open houses
- **Usage counter**: tokens in/out and an estimated cost, in Settings
- 10 backgrounds and an animated kitty mascot

## Getting started

Follow **[SETUP.md](SETUP.md)**: it takes about 30 minutes the first time.
You need:

- Android Studio and an Android phone (Android 8 or newer)
- An OpenAI API key from https://platform.openai.com (pay-as-you-go)
- For Gmail features: a free Google Cloud project (steps in SETUP.md)

Then edit `app/src/main/assets/about_me.md` with facts about you (your city, languages…),
so the kitty knows you from the first chat.

## What it costs

You pay OpenAI directly for what you use. The default model is `gpt-6-luna`, the
cheapest one: a normal chat message costs a small fraction of a cent. You can change
the model in Settings. Making pictures and web searches cost extra.

## Privacy

- Your API key is stored **encrypted** on the phone (Android Keystore), never in the code.
- Chats, memories and the search index stay **on the phone**.
- What you send in a chat (your messages, photos, and email sender/subject/preview when
  cleaning Gmail) goes to OpenAI so the AI can answer. Full email bodies are never sent.
- Trashing or unsubscribing goes straight from the phone to Gmail, and only after you tap Approve.

## Project layout

| File | What it does |
|---|---|
| `AgentViewModel.kt` | The brain: chat loop, tools, memory, voice |
| `OpenAiClient.kt` | Talks to the OpenAI Responses and Embeddings APIs |
| `GmailClient.kt`, `GoogleAuth.kt` | Gmail search, trash, unsubscribe; Google sign-in |
| `MlsAuth.kt` | Flexmls MCP sign-in (OAuth with PKCE) |
| `ChatStore.kt` | SQLite storage for chats and the past-chat search index |
| `ChatScreen.kt`, `Mascot.kt`, `VoiceOrb.kt` | The screens and the kitty |
| `Prefs.kt` | Settings, with encrypted secrets |

## License

MIT: see [LICENSE](LICENSE). Images were created with ChatGPT for this project.
