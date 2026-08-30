# Eta — Google / Pixel edition

Eta is a root-enabled Android AI agent for Google and AOSP-based devices.

## LSPosed targets

The module uses a user-controlled LSPosed scope. Recommended targets are `system`, `SystemUI`, Google App, Phone by Google, Google Messages, Contacts, Calendar, Keep, Photos, Recorder, and Files by Google. Select only the applications you use and reboot after changing scopes.

## Integration model

- A stable public `Application.onCreate()` hook is installed in selected Google targets.
- The Google App keeps Gemini and contextual-search integration without device-identity spoofing.
- Phone, messages, contacts, calendar, recorder, media, downloads, and files use Android Providers and Intents.
- Keep uses Android sharing/accessibility because it has no universal public content API.

## Privacy

Eta does not read vendor-private databases or third-party messenger caches. Sensitive device tools are disabled unless explicitly enabled in the app.

## Requirements

- Android 14+
- Root access
- LSPosed with LibXposed API 102 support

Build a debug APK with:

```bash
./gradlew :app:assembleDebug
```
