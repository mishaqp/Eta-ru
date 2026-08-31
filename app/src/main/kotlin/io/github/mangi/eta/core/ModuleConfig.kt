package io.github.mangi.eta.core

internal object ModuleConfig {
    const val TAG = "Eta"
    const val HOT_PATH_LOG_WINDOW_MS = 60_000L

    const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
    const val GOOGLE_DIALER_PACKAGE = "com.google.android.dialer"
    const val GOOGLE_MESSAGES_PACKAGE = "com.google.android.apps.messaging"
    const val GOOGLE_CONTACTS_PACKAGE = "com.google.android.contacts"
    const val GOOGLE_CALENDAR_PACKAGE = "com.google.android.calendar"
    const val GOOGLE_KEEP_PACKAGE = "com.google.android.keep"
    const val GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos"
    const val GOOGLE_RECORDER_PACKAGE = "com.google.android.apps.recorder"
    const val GOOGLE_FILES_PACKAGE = "com.google.android.apps.nbu.files"
    val GOOGLE_TARGET_PACKAGES: Set<String> = linkedSetOf(
        GOOGLE_APP_PACKAGE,
        GOOGLE_DIALER_PACKAGE,
        GOOGLE_MESSAGES_PACKAGE,
        GOOGLE_CONTACTS_PACKAGE,
        GOOGLE_CALENDAR_PACKAGE,
        GOOGLE_KEEP_PACKAGE,
        GOOGLE_PHOTOS_PACKAGE,
        GOOGLE_RECORDER_PACKAGE,
        GOOGLE_FILES_PACKAGE,
    )
    const val ETA_PACKAGE = "io.github.mangi.eta"
    val AGENT_RUNTIME_ENTRY_PACKAGES = emptySet<String>()
    const val GOOGLE_ASSISTANT_COMPONENT =
        "$GOOGLE_APP_PACKAGE/com.google.android.voiceinteraction.GsaVoiceInteractionService"
    const val ETA_VOICE_INTERACTION_COMPONENT =
        "$ETA_PACKAGE/io.github.mangi.eta.agent.voice.EtaVoiceInteractionService"
    const val ASSISTANT_ROLE = "android.app.role.ASSISTANT"
    const val SECURE_ASSISTANT = "assistant"
    const val SECURE_VOICE_INTERACTION_SERVICE = "voice_interaction_service"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    const val CONTEXTUAL_SEARCH_ACTION = "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH"
    const val CONTEXTUAL_SEARCH_SERVICE = "contextual_search"
    const val CONTEXTUAL_SEARCH_CLASS =
        "com.android.server.contextualsearch.ContextualSearchManagerService"
    const val TIMINGS_TRACE_AND_SLOG_CLASS = "com.android.server.utils.TimingsTraceAndSlog"
    const val VOICE_INTERACTION_SERVICE = "voiceinteraction"
    const val VOICE_INTERACTION_MANAGER_SERVICE_CLASS =
        "com.android.server.voiceinteraction.VoiceInteractionManagerService"
    const val SYSTEM_SERVER_CLASS = "com.android.server.SystemServer"
    const val PHONE_WINDOW_MANAGER_CLASS = "com.android.server.policy.PhoneWindowManager"

    const val CIRCLE_TO_SEARCH_ENTRYPOINT = 2

    fun isGoogleTargetProcess(processName: String): Boolean = GOOGLE_TARGET_PACKAGES.any { packageName ->
        processName == packageName || processName.startsWith("$packageName:")
    }
}
