# Technical design — Google / Pixel edition

## LSPosed lifecycle

`META-INF/xposed/module.prop` sets `staticScope=false`. LSPosed remains the source of truth for enabled scopes. `scope.list` only provides the recommended Google and system targets shown to the user.

`ModuleMain` retains callbacks only for `system_server`, `SystemUI`, and the configured Google target packages. This prevents the module from running in unrelated selected processes.

## Google target hooks

Every selected Google target receives a minimal hook on the public `Application.onCreate()` lifecycle method. It provides a stable process entry point and diagnostics without depending on obfuscated, version-specific Google classes.

The Google App retains eligibility and voice-command hooks for Gemini and contextual search. Eta does not modify `Build.MANUFACTURER`, model, brand, device, or product fields.

## Supported Android integrations

| Integration | Mechanism |
| --- | --- |
| Phone / call history | Call log provider and `tel:` intents |
| Messages / SMS | SMS provider and system intents |
| Contacts | Contacts provider and lookup URIs |
| Calendar | Calendar provider |
| Recorder / media | MediaStore |
| Files / downloads | MediaStore and Downloads provider |
| Keep | Android share/open flow or accessibility, because no public universal Keep provider exists |

## System integration

System-server hooks keep accessibility protection, assistant role configuration, hotword recovery and contextual-search support. Contextual Search accepts only `SystemUI` as caller; no vendor-specific intermediary is trusted.

## Security boundary

The Agent Runtime accepts its own process only. Selecting an application in LSPosed does not grant that application the right to start Eta runs or send arbitrary IPC payloads. This keeps scope selection independent from the runtime trust boundary.
