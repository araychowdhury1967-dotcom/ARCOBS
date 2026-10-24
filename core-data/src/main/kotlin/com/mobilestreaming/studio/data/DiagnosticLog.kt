package com.mobilestreaming.studio.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded diagnostic log. Callers must never pass credentials or codec/network payloads. */
class DiagnosticLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, "diagnostics/session.log")
    private val initialized = AtomicBoolean(false)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun info(tag: String, message: String) = write(Log.INFO, tag, message)
    fun warning(tag: String, message: String) = write(Log.WARN, tag, message)
    fun error(tag: String, message: String, error: Throwable? = null) = write(
        Log.ERROR,
        tag,
        message + (error?.let { " (${it::class.java.simpleName}: ${it.message.orEmpty()})" } ?: ""),
    )

    @Synchronized
    private fun write(level: Int, tag: String, rawMessage: String) {
        initialize()
        val message = redact(rawMessage).take(MAX_MESSAGE)
        Log.println(level, tag.take(23), message)
        runCatching {
            if (file.length() > MAX_FILE_BYTES) {
                file.renameTo(File(file.parentFile, "session.previous.log"))
                file.writeText("")
            }
            val priority = when (level) {
                Log.INFO -> "I"
                Log.WARN -> "W"
                else -> "E"
            }
            file.appendText("${timeFormat.format(Date())} $priority/$tag ${message}\n")
        }
    }

    @Synchronized
    fun exportTo(output: OutputStream) {
        initialize()
        output.bufferedWriter().use { writer ->
            writer.write("Mobile Streaming Studio diagnostic log\n")
            writer.write("Secrets, stream keys, access tokens and media payloads are not recorded.\n\n")
            val previous = File(file.parentFile, "session.previous.log")
            if (previous.exists()) previous.forEachLine { writer.write(redact(it) + "\n") }
            if (file.exists()) file.forEachLine { writer.write(redact(it) + "\n") }
        }
    }

    private fun initialize() {
        if (initialized.compareAndSet(false, true)) {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.createNewFile()
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 512 * 1024
        private const val MAX_MESSAGE = 2_000
        private val SECRET = Regex(
            "(?i)(stream[_-]?key|access[_-]?token|token|secret|password)(\\s*[=:]\\s*)([^\\s,;]+)",
        )
        private val RTMP_QUERY = Regex("(?i)(rtmps?://[^\\s?]+)\\?[^\\s]+")

        fun redact(value: String): String = SECRET.replace(value, "$1$2<redacted>")
            .replace(RTMP_QUERY, "$1?<redacted>")
    }
}
