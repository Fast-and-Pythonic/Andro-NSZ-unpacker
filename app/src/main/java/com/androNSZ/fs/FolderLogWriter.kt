package com.androNSZ.fs

import android.content.Context
import com.androNSZ.util.LogFiles
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Kotlin-side status log for folder scans and folder/combined conversions.
 *
 * One instance per run: the file is rotated and truncated on construction, so it
 * holds exactly this run (the previous one stays as `*.prev.log`). It used to open
 * in append mode and was never truncated, which let it grow without bound and
 * stack unlabelled banners from every past run. See [LogFiles].
 */
class FolderLogWriter(context: Context, runId: Int, mode: String) {

    private val logFile: File
    private val writer: PrintWriter
    private val mutex = Mutex()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    val logFilePath: String
        get() = logFile.absolutePath

    init {
        val externalFilesDir = context.getExternalFilesDir(null)
        logFile = File(externalFilesDir, LogFiles.FOLDER_LOG)
        LogFiles.rotate(logFile)

        writer = PrintWriter(FileWriter(logFile, false), true)
        writer.print(LogFiles.banner(runId, "Folder processing log", mode))
        writer.flush()
    }

    suspend fun writeLog(tag: String, message: String) {
        mutex.withLock {
            try {
                val timestamp = dateFormat.format(Date())
                val logEntry = "[$timestamp] [$tag] $message"
                writer.println(logEntry)
                writer.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun close() {
        mutex.withLock {
            try {
                val footer = """
                    
                    ========================================
                    Finished: ${dateFormat.format(Date())}
                    Log file: ${logFile.absolutePath}
                    ========================================
                """.trimIndent()
                writer.println(footer)
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
