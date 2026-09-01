package io.github.mangi.eta.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Persistent, bounded, privacy-filtered log ring used for support reports. */
internal object DiagnosticLogStore {
    private const val MAX_LOG_BYTES = 2 * 1024 * 1024
    private const val MAX_REPORT_BYTES = 1024 * 1024
    private const val MAX_LOGCAT_CHARS = 240_000
    private const val MAX_THROWABLE_CHARS = 16_000
    private const val LOG_FILE_NAME = "eta-agent.log"
    private const val COLLECTION_FLAG_NAME = "collecting.flag"
    private val lock = Any()
    private var logFile: File? = null
    private var collectionFlag: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (logFile == null) {
                val directory = File(context.applicationContext.filesDir, "diagnostics")
                logFile = File(directory, LOG_FILE_NAME)
                collectionFlag = File(directory, COLLECTION_FLAG_NAME)
            }
        }
    }

    /** True while the user has explicitly enabled a diagnostic capture session. */
    fun isCollecting(): Boolean = synchronized(lock) {
        collectionFlag?.isFile == true
    }

    /** Starts a fresh capture session and removes the previous session's events. */
    fun startCollection() {
        synchronized(lock) {
            val file = logFile ?: return
            val flag = collectionFlag ?: return
            runCatching {
                file.parentFile?.mkdirs()
                file.delete()
                flag.writeText("started_at=${timestamp()}\n", Charsets.UTF_8)
            }
        }
    }

    /** Stops capture but keeps the collected events available for sharing. */
    fun stopCollection() {
        synchronized(lock) {
            collectionFlag?.delete()
        }
    }

    fun append(level: String, message: String, throwable: Throwable? = null) {
        synchronized(lock) {
            val file = logFile ?: return
            // The flag is a file so every Eta process sees the same user choice.
            if (collectionFlag?.isFile != true) return
            val safeLevel = level.toSafeLogToken(8).uppercase(Locale.US)
            val safeMessage = redact(message).replace(Regex("[\\r\\n\\u0000]+"), " ").trim()
            val suffix = throwable?.let {
                val stack = redact(it.stackTraceToString())
                    .replace(Regex("[\\r\\n\\u0000]+"), " ")
                    .take(MAX_THROWABLE_CHARS)
                " exception=${it.safeLogType()} stack=$stack"
            }.orEmpty()
            val line = "${timestamp()} $safeLevel $safeMessage$suffix\n"
            runCatching {
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
                trimIfNeeded(file)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            logFile?.delete()
            collectionFlag?.delete()
        }
    }

    fun snapshot(): String = synchronized(lock) {
        val file = logFile ?: return@synchronized ""
        runCatching {
            file.takeIf(File::isFile)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        }.getOrDefault("")
    }

    fun buildReport(context: Context): File {
        val appContext = context.applicationContext
        init(appContext)
        val reportDir = File(appContext.cacheDir, "diagnostics").also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val report = File(reportDir, "Eta-diagnostics-$stamp.txt")
        val body = buildString {
            appendLine("Eta diagnostic report")
            appendLine("generated_at=${timestamp()}")
            appendLine("package=${appContext.packageName}")
            runCatching {
                val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                appendLine("version_name=${redact(info.versionName.orEmpty())}")
                appendLine("version_code=${info.longVersionCode}")
            }
            appendLine("android_sdk=${Build.VERSION.SDK_INT}")
            appendLine("android_release=${redact(Build.VERSION.RELEASE.orEmpty())}")
            appendLine("device=${redact(Build.MANUFACTURER)} ${redact(Build.MODEL)}")
            appendLine("process=${redact(android.app.Application.getProcessName())}")
            appendLine("collection_active=${isCollecting()}")
            appendLine()
            appendLine("Privacy: chat messages, MEMORY.md, provider configuration, API keys and file contents are omitted.")
            appendLine()
            appendLine("[eta_agent_log]")
            append(snapshot())
            appendLine()
            appendLine("[logcat_eta_tag]")
            appendLine(readEtaLogcat())
        }.toString()
        val bytes = redact(body).toByteArray(Charsets.UTF_8)
        val bounded = if (bytes.size <= MAX_REPORT_BYTES) {
            bytes
        } else {
            val header = "Eta diagnostic report truncated to 1 MiB\n\n".toByteArray(Charsets.UTF_8)
            header + bytes.takeLast(MAX_REPORT_BYTES - header.size).toByteArray()
        }
        report.writeBytes(bounded)
        return report
    }

    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_LOG_BYTES) return
        val bytes = file.readBytes()
        val keep = bytes.takeLast(MAX_LOG_BYTES / 2)
        file.writeBytes("[diagnostic log rotated]\n".toByteArray(Charsets.UTF_8) + keep)
    }

    private fun readEtaLogcat(): String {
        val process = runCatching {
            Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "2000", "-s", "Eta:*"))
        }.getOrNull() ?: return "logcat_unavailable"
        return runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
                .takeLast(MAX_LOGCAT_CHARS)
                .let(::redact)
        }.getOrElse { "logcat_read_failed=${it.safeLogType()}" }
            .also { process.destroy() }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    internal fun redact(value: String): String {
        var result = value
        result = result.replace(
            Regex("(?i)(api[_-]?key|token|authorization|password|passwd|secret|bearer)(\\s*[:=]\\s*)(?:bearer\\s+)?([^\\s,;]+)"),
            "$1$2[REDACTED]",
        )
        result = result.replace(Regex("(?i)\\b(sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9_]{12,}|AIza[A-Za-z0-9_-]{20,})\\b"), "[REDACTED]")
        return result
    }
}
