# My Agent: setup

A personal AI agent for Android. It chats using your OpenAI model, searches the web,
remembers facts you tell it, and cleans junk out of your Gmail. It never deletes email
on its own. It shows you a list first, and only moves emails to Trash when you tap
**Move to Trash**.

Setup takes about 30 minutes the first time.

## 1. Install Android Studio

Download it free from https://developer.android.com/studio and install it with the
default options.

## 2. Open the project

1. Download or clone this project (or unzip it).
2. In Android Studio: **File > Open**, pick the project folder (open `settings.gradle.kts` if it won't accept the folder).
3. Wait for the "Gradle sync" bar at the bottom to finish (several minutes the first time).
   If it offers to upgrade the Android Gradle Plugin, you can accept.

## 3. Let the app use Gmail (Google Cloud, one time)

Google requires every app that reads Gmail to be registered. This is free.

**a. Get your app's fingerprint (SHA-1).**
In Android Studio, open the **Terminal** tab at the bottom and run:

- Windows: `gradlew signingReport`
- Mac: `./gradlew signingReport`

Find the `debug` section and copy the line that starts with `SHA1:`
(looks like `AB:CD:12:...`).

**b. Set up Google Cloud.** Go to https://console.cloud.google.com and sign in with
the Gmail account you want to clean.

1. Create a new project (top-left project menu > **New project**), name it "My Agent".
2. Search for **Gmail API** in the top search bar, open it, click **Enable**.
3. Open **Google Auth Platform** (or "OAuth consent screen") and click **Get started**:
   - App name: My Agent. Support email: your email.
   - Audience: **External**.
   - Contact email: your email. Agree and create.
4. Under **Audience > Test users**, click **Add users** and add your own Gmail address.
5. Under **Data access**, click **Add or remove scopes**, search for
   `https://www.googleapis.com/auth/gmail.modify`, tick it and save.
6. Under **Clients**, click **Create client**:
   - Application type: **Android**
   - Package name: `com.myagent.app`
   - SHA-1: paste the value from step a.
   - Create.

## 4. Put it on your phone

1. On the phone: **Settings > About phone**, tap **Build number** 7 times to unlock
   Developer options. Then in **Settings > System > Developer options**, turn on
   **USB debugging**.
2. Plug the phone into the computer and allow the prompt on the phone.
3. In Android Studio, pick your phone in the device list at the top and press the
   green **Run** button. The app installs and opens.

To make an installable file instead: **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
The APK lands in `app/build/outputs/apk/debug/`.

## 5. First run

1. The Settings box opens. Paste your OpenAI API key (from
   https://platform.openai.com/api-keys) and check the model name. Tap **Save**.
2. Try: *"Clean up my promotional emails older than 2 weeks."*
3. The first time it touches Gmail, Google asks you to pick your account. You will see
   "Google hasn't verified this app". That's expected for your own private app:
   tap **Continue** and allow access.
4. Review the list, uncheck anything you want to keep, then tap **Move N to Trash**.

## Good to know

- **Nothing is permanently deleted.** Emails go to Gmail's Trash, where you can
  restore them for 30 days. The app's Gmail permission can't permanently delete mail.
- **Starred emails are never trashed**, even if the AI suggests them. The AI can only
  propose emails it actually found in a search, at most 100 at a time.
- **Your API key stays on your phone.** Don't share the APK with anyone else.
- **If Gmail sign-in fails** with "DEVELOPER_ERROR" or code 10, the package name or
  SHA-1 in step 3 doesn't match. Re-run `signingReport` and check.
- Because the Google app is in "Testing" mode, Google may ask you to approve access
  again about once a week. Just tap Continue.
- **"Model not found" error:** open Settings and enter a model your OpenAI account has,
  such as one listed at https://platform.openai.com/docs/models.

## Where things live in the code

| File | What it does |
| --- | --- |
| `AgentViewModel.kt` | The agent: instructions, tools, and the think, act, repeat loop |
| `OpenAiClient.kt` | Calls the OpenAI Responses API |
| `GmailClient.kt` | Searches Gmail and moves mail to Trash |
| `GoogleAuth.kt` | Google account sign-in and Gmail permission |
| `ChatScreen.kt` | The chat screen, approval card and Settings |
| `Prefs.kt` | Saved key, model, agent name and memories |

To add a new ability, add a tool in `tools()` and handle it in `runTool()` inside
`AgentViewModel.kt`. Anything that sends, buys or deletes should go through an
approval card like `propose_trash`.
