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
      val isXcz: Boolean = false
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
