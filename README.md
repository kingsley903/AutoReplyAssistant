# Auto Reply Assistant (Android)

A Kotlin + Jetpack Compose Android app for:
- Auto-replying to WhatsApp, WhatsApp Business, and Facebook Messenger message notifications using notification `RemoteInput` reply actions.
- Auto-answering standard carrier phone calls through Android `InCallService`, only after the user selects this app as the default phone app.
- Saving settings with Preferences DataStore.
- Building a debug APK in GitHub Actions without Android Studio.

## Requirements

- Android 8.0 (API 26) or newer. The stated Android 9 device is supported.
- JDK 17 for building.
- Android SDK platform 34 and build tools supplied by the CI setup action.
- A repository on GitHub to run the included workflow.

## Build and download without Android Studio

1. Create a GitHub repository and upload the complete contents of this project.
2. Push to any branch, or open **Actions → Build Android APK → Run workflow**.
3. Open the completed workflow run.
4. Download the `auto-reply-assistant-debug-apk` artifact.
5. Extract it and install `app-debug.apk` on the Android device. You may need to allow installation from the source used to open the APK.

The workflow sets up JDK 17 and Gradle 8.7, generates the binary Gradle wrapper JAR if it is absent, sets up Android SDK, runs `./gradlew assembleDebug`, and uploads the APK.

## First-time Android setup

1. Open the app.
2. Tap **Enable Notification Access** and enable Auto Reply Assistant.
3. Configure the API endpoint, API key, model, prompt, delay and ignored contacts, then save.
4. Enable **WhatsApp Auto-Reply**.
5. For carrier-call answering, grant the requested phone permissions, tap **Set as Default Phone App**, and select this app. Then enable **Auto-Pick Incoming Calls**.

The app does not detect, answer, or inject audio into WhatsApp calls.

## WhatsApp and Messenger behavior and limitations

- Only notifications from `com.whatsapp`, `com.whatsapp.w4b`, `com.facebook.orca`, and `com.facebook.lite` are considered.
- Notifications must expose a text body and a `RemoteInput` reply action. Some WhatsApp/Messenger notification formats, muted chats, device/vendor variants or app versions may not expose a compatible action; those notifications cannot be replied to by this app.
- Android notification access is a sensitive special access that the user must explicitly grant in system settings.
- A reply is generated through the configured OpenAI-compatible chat-completions endpoint. Keep the API key private.
- Recent message context and duplicate/cooldown tracking are in-memory only and are cleared when Android kills the service or the app process restarts.
- Ignored contacts are matched as case-insensitive substrings of the notification title. Use comma-separated names.
- A per-contact cooldown of at least five seconds is enforced. The configured reply delay is capped at 60 seconds.
- The app does not guarantee delivery; notification actions can expire or become invalid before a delayed reply is sent.

## Incoming phone calls

Android only binds an `InCallService` for the selected default dialer. The app declares the dialer intent filters and InCallService metadata, but it is a minimal automation-focused dialer and is not a replacement for a full-featured phone application. Some Android/OEM dialer qualification flows may reject a minimal dialer or require additional call UI behavior. If Android does not accept it as the default phone app, automatic call answering will not be available on that device.

The `ANSWER_PHONE_CALLS` and `READ_PHONE_STATE` runtime permissions are requested by the app. `POST_NOTIFICATIONS` is requested on Android 13 and newer; Android 9 does not have this runtime permission.

## Security notes

- The API key is stored in app-private Preferences DataStore. It is not encrypted by this implementation. Use a dedicated, restricted API key and do not share device backups or app data.
- HTTPS is recommended and used by the default endpoint. HTTP URLs are accepted by the code for compatible self-hosted endpoints, but should not be used over untrusted networks.
- AI-generated messages may be inaccurate or inappropriate. Review your system prompt and ignored-contact list carefully. The feature sends replies automatically when enabled.
- The in-memory activity log may contain sender names and operational details. It is not persisted to disk.

## Project layout

```text
.
├── .github/workflows/build-apk.yml
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/example/autoreplyassistant/
│           ├── data/SettingsRepository.kt
│           ├── data/ActivityLogStore.kt
│           ├── network/ReplyGenerator.kt
│           ├── service/WhatsAppReplyListenerService.kt
│           ├── service/AutoPickCallService.kt
│           └── ui/MainActivity.kt
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew
├── gradlew.bat
└── settings.gradle.kts
```
