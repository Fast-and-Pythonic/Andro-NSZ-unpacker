package com.androNSZ.fs

import android.content.Context
import java.io.File
import java.util.UUID

object TempFileManager {

    private val cleanupExtensions = setOf("nsz", "nsp")

    fun createManagedTempFile(
        context: Context,
        originalName: String,
        extensionHint: String? = null
    ): File {
        val extension = extensionHint
            ?.trimStart('.')
            ?.takeIf { it.isNotBlank() }
            ?: originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            ?: "tmp"

        val safeBaseName = originalName
            .substringBeforeLast('.')
            .ifBlank { "temp" }
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .take(80)

        return File(
            context.cacheDir,
            "andronsz_${UUID.randomUUID()}_${safeBaseName}.$extension"
        )
    }

    fun deleteQuietly(file: File?): Boolean {
        if (file == null || !file.exists()) return true
        return runCatching { file.delete() }.getOrDefault(false)
    }

    fun cleanupManagedCache(context: Context): Int {
        var deletedCount = 0

        for (file in context.cacheDir.listFiles().orEmpty()) {
            if (!file.isFile) continue

            val shouldDelete = file.name.startsWith("andronsz_") ||
                file.extension.lowercase() in cleanupExtensions
            if (!shouldDelete) continue

            if (deleteQuietly(file)) {
                deletedCount++
            }
        }

        return deletedCount
    }
}
