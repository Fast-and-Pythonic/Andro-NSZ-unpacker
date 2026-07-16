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
   val unpackedSize: Long? = null,
   // Outcome of the post-unpack CNMT hash check — drives the card's second status
   // word and its colour. NOT_CHECKED when verification didn't run (disabled, no
   // header_key, XCZ), CHECKED when it passed, FAILED on a hash mismatch.
   val verify: VerifyStatus = VerifyStatus.NOT_CHECKED
)

enum class FileStatus {
   Pending,
   Converting,
   Completed,
   Failed
}

enum class VerifyStatus {
   CHECKED,       // verification ran and passed
   NOT_CHECKED,   // verification didn't run (disabled / no header_key / XCZ)
   FAILED         // verification ran and the hash mismatched
}
