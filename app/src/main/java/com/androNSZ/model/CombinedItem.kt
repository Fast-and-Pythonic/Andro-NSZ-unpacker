package com.androNSZ.model

import java.io.File

/**
 * One entry in the combined-mode selection list: either a standalone file or a
 * whole folder. Folders are scanned once when added ([structure] caches the
 * result) so removing/re-merging the selection never re-walks the filesystem.
 * [nszCount]/[xczCount] are the aggregate counts shown on a folder container's
 * header; for a standalone file they describe that single file.
 */
data class CombinedItem(
   val file: File,
   val isDirectory: Boolean,
   val structure: FolderStructure?,
   val sizeBytes: Long,
   val nszCount: Int,
   val xczCount: Int
)
