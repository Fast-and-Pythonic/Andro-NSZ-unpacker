package com.androNSZ.model

import android.net.Uri

sealed class ConversionMode {
   object None : ConversionMode()
   data class SingleFiles(val files: List<FileEntry>) : ConversionMode()
   data class FolderMode(val folderUri: Uri, val structure: FolderStructure?) : ConversionMode()
}
