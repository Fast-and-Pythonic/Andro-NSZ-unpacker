package com.androNSZ.fs

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FolderLogWriter(context: Context) {

    private val logFile: File
    private val writer: PrintWriter
    private val mutex = Mutex()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    val logFilePath: String
        get() = logFile.absolutePath

    init {
        val externalFilesDir = context.getExternalFilesDir(null)
        logFile = File(externalFilesDir, "nsz_folder_debug.log")

        writer = PrintWriter(FileWriter(logFile, true), true)

        val header = """
            ========================================
            AndroNSZ Folder Processing Log
            Started: ${dateFormat.format(Date())}
            ========================================
            
        """.trimIndent()
        writer.println(header)
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
