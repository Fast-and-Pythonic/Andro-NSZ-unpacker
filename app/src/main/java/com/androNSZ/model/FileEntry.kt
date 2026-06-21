package com.androNSZ.model

import android.net.Uri

data class FileEntry(
   val uri: Uri,
   val displayName: String,
   val fileSize: Long = 0L,
   val status: FileStatus = FileStatus.Pending,
   // Filled in once the file finishes unpacking: how long it took, the average
   // unpack speed over that time, and the resulting (uncompressed) size. Null
   // while pending/converting/failed. fileSize is the compressed "before" size.
   val unpackDurationMs: Long? = null,
   val unpackSpeedMBps: Double? = null,
   val unpackedSize: Long? = null
)

enum class FileStatus {
   Pending,
   Converting,
   Completed,
   Failed
}
