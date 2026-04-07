package com.androNSZ.model

import android.net.Uri

data class FileEntry(
   val uri: Uri,
   val displayName: String,
   val fileSize: Long = 0L,
   val status: FileStatus = FileStatus.Pending
)

enum class FileStatus {
   Pending,
   Converting,
   Completed,
   Failed
}
