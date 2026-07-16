package com.androNSZ.model

import android.net.Uri

data class FolderStructure(
   val rootUri: Uri,
   val nszFiles: List<Uri>,
   val xczFiles: List<Uri> = emptyList(),
   val allFiles: List<FileNode>,
   val totalSize: Long
)

sealed class FileNode {
   data class File(
      val uri: Uri,
      val name: String,
      val isNsz: Boolean,
      val isXcz: Boolean = false,
      val sizeBytes: Long = 0L
   ) : FileNode()
   data class Directory(val name: String, val children: List<FileNode>) : FileNode()
}

fun countAllFiles(nodes: List<FileNode>): Int {
   var count = 0
   for (node in nodes) {
      when (node) {
         is FileNode.File -> count++
         is FileNode.Directory -> count += countAllFiles(node.children)
      }
   }
   return count
}

/** Flattens the folder tree into the NSZ/XCZ files that will be unpacked. */
fun collectCompressedFiles(nodes: List<FileNode>): List<FileNode.File> {
   val result = mutableListOf<FileNode.File>()
   for (node in nodes) {
      when (node) {
         is FileNode.File -> if (node.isNsz || node.isXcz) result.add(node)
         is FileNode.Directory -> result.addAll(collectCompressedFiles(node.children))
      }
   }
   return result
}

/**
 * Flattens the folder tree into every file — NSZ/XCZ (unpacked) and everything
 * else (copied). Drives the per-file cards so copied files are tracked too.
 */
fun collectAllFiles(nodes: List<FileNode>): List<FileNode.File> {
   val result = mutableListOf<FileNode.File>()
   for (node in nodes) {
      when (node) {
         is FileNode.File -> result.add(node)
         is FileNode.Directory -> result.addAll(collectAllFiles(node.children))
      }
   }
   return result
}
