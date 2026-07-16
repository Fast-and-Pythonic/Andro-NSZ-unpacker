package com.androNSZ.fs

import android.net.Uri
import com.androNSZ.NszConverter
import com.androNSZ.model.FileNode
import com.androNSZ.model.FolderStructure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Raw-filesystem counterpart of [FolderScanner]. Walks a [java.io.File] tree and
 * produces the same [FolderStructure] shape (with file:// uris), so everything
 * downstream — [com.androNSZ.model.collectCompressedFiles], MainViewModel's
 * folder-file list, and [FolderProcessor] — works unchanged. Used by the in-app
 * picker in folder mode, where the source folder is a real path rather than a SAF
 * tree.
 */
object RawFolderScanner {

   suspend fun scanFolder(
      folder: File,
      statusCallback: NszConverter.StatusCallback? = null
   ): FolderStructure = withContext(Dispatchers.IO) {
      val nszFiles = mutableListOf<Uri>()
      val xczFiles = mutableListOf<Uri>()
      val totalSizeRef = LongArray(1)
      val fileCountRef = IntArray(1)
      val folderCountRef = IntArray(1)

      statusCallback?.onStatus("SCAN", "Starting folder scan...")
      val startTime = System.currentTimeMillis()

      val tree = scanDirectory(
         folder, nszFiles, xczFiles, fileCountRef, folderCountRef, statusCallback
      ) { size -> totalSizeRef[0] += size }

      val elapsedMs = System.currentTimeMillis() - startTime
      val totalSizeMB = totalSizeRef[0] / 1024.0 / 1024.0
      statusCallback?.onStatus("SCAN", "Scan completed in ${elapsedMs}ms")
      statusCallback?.onStatus("INFO", "Found: ${fileCountRef[0]} files, ${folderCountRef[0]} folders")
      statusCallback?.onStatus("INFO", "NSZ files: ${nszFiles.size}, XCZ files: ${xczFiles.size}, total size: %.2f MB".format(totalSizeMB))

      FolderStructure(
         rootUri = Uri.fromFile(folder),
         nszFiles = nszFiles,
         xczFiles = xczFiles,
         allFiles = tree,
         totalSize = totalSizeRef[0]
      )
   }

   private fun scanDirectory(
      dir: File,
      nszFiles: MutableList<Uri>,
      xczFiles: MutableList<Uri>,
      fileCountRef: IntArray,
      folderCountRef: IntArray,
      statusCallback: NszConverter.StatusCallback?,
      addToTotalSize: (Long) -> Unit
   ): List<FileNode> {
      val results = mutableListOf<FileNode>()

      // Folders first, then files; both alphabetical, matching the picker's order.
      val children = dir.listFiles()?.sortedWith(
         compareBy({ !it.isDirectory }, { it.name.lowercase() })
      ) ?: emptyList()

      for (child in children) {
         if (child.isDirectory) {
            folderCountRef[0]++
            statusCallback?.onStatus("SCAN", "Found folder: ${child.name}")
            val nested = scanDirectory(
               child, nszFiles, xczFiles, fileCountRef, folderCountRef, statusCallback, addToTotalSize
            )
            results.add(FileNode.Directory(child.name, nested))
         } else {
            fileCountRef[0]++
            val name = child.name
            val isNsz = name.endsWith(".nsz", ignoreCase = true)
            val isXcz = name.endsWith(".xcz", ignoreCase = true)
            val size = child.length()
            val fileUri = Uri.fromFile(child)

            if (isNsz) nszFiles.add(fileUri)
            else if (isXcz) xczFiles.add(fileUri)

            results.add(FileNode.File(fileUri, name, isNsz, isXcz, size))
            addToTotalSize(size)
         }
      }

      return results
   }
}
