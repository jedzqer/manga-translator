package com.manga.translate

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AppLogger {
    private const val MAX_LOG_BYTES = 1_000_000
    private const val MAX_LOG_FILES = 15
    private const val LEVEL_INFO = "I"
    private const val LEVEL_ERROR = "E"
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileNameFormatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US)
    private var logDir: File? = null
    private var logFile: File? = null

    fun init(context: Context) {
        val externalRoot = context.getExternalFilesDir(null)?.parentFile
        val dir = if (externalRoot != null) {
            File(externalRoot, "log")
        } else {
            File(context.filesDir, "logs")
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        logDir = dir
        logFile = createNewLogFile(dir)
        log("AppLogger", "Logger initialized")
        cleanupOldLogs()
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val level = if (throwable != null) LEVEL_ERROR else LEVEL_INFO
        writeLog(level, tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        writeLog(LEVEL_ERROR, tag, message, throwable)
    }

    private fun writeLog(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        val file = logFile ?: return
        val time = formatter.format(Date())
        val separator = "  "
        val line = buildString {
            append('[')
            append(level)
            append(']')
            append('[')
            append(tag)
            append("] ")
            append(time)
            append(separator)
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable::class.java.simpleName)
                append(": ")
                append(throwable.message ?: "no message")
            }
            append('\n')
        }
        synchronized(this) {
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                file.writeText("[AppLogger] $time${separator}Log rotated\n")
            }
            file.appendText(line)
        }
    }

    fun readLogs(): String {
        val file = logFile ?: return ""
        return if (file.exists()) file.readText() else ""
    }

    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("log", true) }
            ?.toList()
            .orEmpty()
        return files.sortedBy { it.name }
    }

    fun listErrorLogFiles(): List<File> {
        return listLogFiles().filter(::containsErrorEntries)
    }

    fun createErrorLogsArchive(context: Context): File? {
        val errorFiles = listErrorLogFiles()
        if (errorFiles.isEmpty()) return null
        val dir = logDir ?: File(context.filesDir, "logs").also { it.mkdirs() }
        val archive = File(dir, "error_logs_${fileNameFormatter.format(Date())}.zip")
        return try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(archive))).use { zip ->
                for (file in errorFiles) {
                    if (!file.exists()) continue
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
            if (archive.exists() && archive.length() > 0L) archive else null
        } catch (_: Exception) {
            if (archive.exists()) {
                archive.delete()
            }
            null
        }
    }

    private fun containsErrorEntries(file: File): Boolean {
        return try {
            file.useLines { lines ->
                lines.any { line ->
                    line.startsWith("[$LEVEL_ERROR]") || line.contains(LEGACY_EXCEPTION_MARKER)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun createNewLogFile(dir: File): File {
        val base = "app_${fileNameFormatter.format(Date())}"
        var candidate = File(dir, "$base.log")
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$index.log")
            index += 1
        }
        return candidate
    }

    private fun cleanupOldLogs() {
        val dir = logDir ?: return
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("log", true) }
            ?.sortedByDescending { it.name }
            .orEmpty()
        if (files.size <= MAX_LOG_FILES) return
        for (file in files.drop(MAX_LOG_FILES)) {
            file.delete()
        }
    }

    private const val LEGACY_EXCEPTION_MARKER = "Exception:"
}
