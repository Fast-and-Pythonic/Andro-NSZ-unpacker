package com.androNSZ.model

import android.net.Uri

sealed class ConversionMode {
   object None : ConversionMode()
   data class SingleFiles(val files: List<FileEntry>) : ConversionMode()
   data class FolderMode(val folderUri: Uri, val structure: FolderStructure?) : ConversionMode()
   // Combined files + folders. Like the two above, the live selection lives in
   // MainViewModel (combinedItems), so this only tags the mode for routing.
   object Combined : ConversionMode()
}
