package com.androNSZ

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Класс для записи логов процесса обработки папки в файл
 */
class FolderLogWriter(context: Context) {
    
    private val logFile: File
    private val writer: PrintWriter
    private val mutex = Mutex()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    val logFilePath: String
        get() = logFile.absolutePath
    
    init {
        // Создать файл в папке Android/data/com.androNSZ/files с фиксированным именем
        val externalFilesDir = context.getExternalFilesDir(null)
        logFile = File(externalFilesDir, "nsz_folder_debug.log")
        
        // Открыть writer для записи
        writer = PrintWriter(FileWriter(logFile, true), true)
        
        // Записать заголовок
        val header = """
            ========================================
            AndroNSZ Folder Processing Log
            Started: ${dateFormat.format(Date())}
            ========================================
            
        """.trimIndent()
        writer.println(header)
        writer.flush()
    }
    
    /**
     * Записать запись в лог (thread-safe)
     */
    suspend fun writeLog(tag: String, message: String) {
        mutex.withLock {
            try {
                val timestamp = dateFormat.format(Date())
                val logEntry = "[$timestamp] [$tag] $message"
                writer.println(logEntry)
                writer.flush()
            } catch (e: Exception) {
                // Игнорируем ошибки записи в лог
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Закрыть writer и завершить запись
     */
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
