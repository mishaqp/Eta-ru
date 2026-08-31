# Eta RU — Google / Pixel edition

Eta is a root-enabled Android AI agent for Google and AOSP-based devices. It uses LibXposed/LSPosed for system integration and standard Android APIs for personal-data and app actions.

## What changed

- Google and Pixel targets replace all device-vendor assistant hooks.
- Recommended LSPosed scopes: `system`, `SystemUI`, Google App, Google Phone, Google Messages, Contacts, Calendar, Keep, Photos, Recorder and Files by Google.
- The LSPosed scope is user-controlled (`staticScope=false`); select only the apps you use.
- Phone, messages, contacts, calendar, media and files use supported Android Providers and Intents. No private Google app APIs are assumed.
- Google Keep is opened through standard sharing or accessibility flows; Keep does not expose a universal public content API.
- Device identity spoofing and Google App systemization were removed. A Pixel does not need to impersonate another device.

## Requirements

- Android 14 or newer
- Root access
- LSPosed compatible with LibXposed API 102

## Recommended setup on Pixel

1. Install Eta and enable the module in LSPosed.
2. In LSPosed, select `system`, `com.android.systemui`, and the Google apps that you actually use.
3. Reboot the phone after changing scopes.
4. Grant Eta only the Android permissions required for the tools you enable.
5. Choose Eta or Gemini as the default assistant in Android settings if you want assistant-role integration.

## Privacy and limits

Personal-data tools are disabled unless enabled in Eta settings. Calls, SMS, contacts and calendar are read through Android system providers. Eta no longer reads private third-party messenger caches or vendor-private databases.

## Build

```bash
./gradlew :app:assembleDebug
```

For a signed release, use the repository's `Eta Build (single signed APK)` GitHub Actions workflow.
