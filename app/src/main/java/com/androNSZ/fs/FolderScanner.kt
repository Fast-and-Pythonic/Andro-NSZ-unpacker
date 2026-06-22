package com.androNSZ.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.androNSZ.NszConverter
import com.androNSZ.model.FileNode
import com.androNSZ.model.FolderStructure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FolderScanner {

    suspend fun scanFolder(
        context: Context,
        folderUri: Uri,
        statusCallback: NszConverter.StatusCallback? = null
    ): FolderStructure = withContext(Dispatchers.IO) {
        val nszFiles = mutableListOf<Uri>()
        val xczFiles = mutableListOf<Uri>()
        val totalSizeRef = LongArray(1)
        val fileCountRef = IntArray(1)
        val folderCountRef = IntArray(1)

        statusCallback?.onStatus("SCAN", "Starting folder scan...")
        val startTime = System.currentTimeMillis()

        val documentId = DocumentsContract.getTreeDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            folderUri,
            documentId
        )

        val tree = scanDirectory(
            context,
            folderUri,
            childrenUri,
            nszFiles,
            xczFiles,
            fileCountRef,
            folderCountRef,
            statusCallback
        ) { size ->
            totalSizeRef[0] += size
        }

        val elapsedMs = System.currentTimeMillis() - startTime
        val totalSizeMB = totalSizeRef[0] / 1024.0 / 1024.0
        statusCallback?.onStatus("SCAN", "Scan completed in ${elapsedMs}ms")
        statusCallback?.onStatus("INFO", "Found: ${fileCountRef[0]} files, ${folderCountRef[0]} folders")
        statusCallback?.onStatus("INFO", "NSZ files: ${nszFiles.size}, XCZ files: ${xczFiles.size}, total size: %.2f MB".format(totalSizeMB))

        FolderStructure(
            rootUri = folderUri,
            nszFiles = nszFiles,
            xczFiles = xczFiles,
            allFiles = tree,
            totalSize = totalSizeRef[0]
        )
    }

    private fun scanDirectory(
        context: Context,
        treeUri: Uri,
        uri: Uri,
        nszFiles: MutableList<Uri>,
        xczFiles: MutableList<Uri>,
        fileCountRef: IntArray,
        folderCountRef: IntArray,
        statusCallback: NszConverter.StatusCallback?,
        addToTotalSize: (Long) -> Unit
    ): List<FileNode> {
        val results = mutableListOf<FileNode>()

        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn)
                val mimeType = cursor.getString(mimeColumn)
                val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    cursor.getLong(sizeColumn)
                } else {
                    0L
                }

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folderCountRef[0]++
                    statusCallback?.onStatus("SCAN", "Found folder: $name")

                    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri,
                        docId
                    )
                    val children = scanDirectory(
                        context,
                        treeUri,
                        childUri,
                        nszFiles,
                        xczFiles,
                        fileCountRef,
                        folderCountRef,
                        statusCallback,
                        addToTotalSize
                    )
                    results.add(FileNode.Directory(name, children))
                } else {
                    fileCountRef[0]++
                    val isNsz = name.endsWith(".nsz", ignoreCase = true)
                    val isXcz = name.endsWith(".xcz", ignoreCase = true)
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                    val sizeMB = size / 1024.0 / 1024.0
                    val fileType = when {
                        isNsz -> "NSZ"
                        isXcz -> "XCZ"
                        else -> name.substringAfterLast('.', "file")
                    }
                    statusCallback?.onStatus("SCAN", "Found file: $name (%.2f MB, $fileType)".format(sizeMB))

                    if (isNsz) {
                        nszFiles.add(fileUri)
                    } else if (isXcz) {
                        xczFiles.add(fileUri)
                    }

                    results.add(FileNode.File(fileUri, name, isNsz, isXcz, size))
                    addToTotalSize(size)
                }
            }
        }

        return results
    }
}
