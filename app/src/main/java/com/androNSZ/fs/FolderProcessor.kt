package com.androNSZ.fs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.androNSZ.NszConverter
import com.androNSZ.model.*
import com.androNSZ.util.ProgressThrottler
import com.androNSZ.util.ResolvedInputFile
import com.androNSZ.util.getUriSize
import com.androNSZ.util.resolveToFilePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object FolderProcessor {

   /**
    * One flattened unit of work, with its output location already resolved. The
    * output folder tree is created up front (sequentially) so the file work can
    * then run in parallel without racing on directory creation.
    */
   private data class WorkItem(
      val sourceUri: Uri,
      val name: String,
      val destParentUri: Uri,
      val destRelativePath: String?,
      val isNsz: Boolean,
      val isXcz: Boolean,
      val size: Long
   )

   suspend fun processFolder(
      context: Context,
      structure: FolderStructure,
      outputBaseUri: Uri?,
      // How many files to convert in parallel, resolved by the caller
      // (MainViewModel.resolveConcurrency).
      concurrency: Int,
      progressCallback: (FolderProgressUpdate) -> Unit,
      statusCallback: NszConverter.StatusCallback?,
      // Per-file lifecycle updates for the "files to unpack" list (NSZ/XCZ only).
      fileEventCallback: ((FolderFileEvent) -> Unit)? = null
   ): Result<Pair<Uri, FolderConversionSummary>> = withContext(Dispatchers.IO) {
      statusCallback?.onStatus("INFO", "Starting folder processing")
      statusCallback?.onStatus("INFO", "Total files in folder: ${structure.allFiles.size}")
      statusCallback?.onStatus("INFO", "NSZ files to convert: ${structure.nszFiles.size}")
      statusCallback?.onStatus("INFO", "Total size: %.2f MB".format(structure.totalSize / 1024.0 / 1024.0))
      statusCallback?.onStatus("INFO", "Parallelism: $concurrency")

      val outputFolderName = generateOutputFolderName(context, structure.rootUri, outputBaseUri)
      statusCallback?.onStatus("FOLDER", "Creating output folder: $outputFolderName")

      val outputFolderUri = if (outputBaseUri != null) {
         createOutputFolderInSaf(context, outputBaseUri, outputFolderName)
      } else {
         createOutputFolder(context, outputFolderName)
      }
      if (outputFolderUri == null) {
         statusCallback?.onStatus("ERROR", "Failed to create output folder")
         return@withContext Result.failure(Exception("Cannot create output folder"))
      }

      statusCallback?.onStatus("FOLDER", "Output folder created: $outputFolderUri")

      // Phase 1: build the output directory tree and a flat list of work items.
      // Folder creation is sequential so phase 2 never races to create the same
      // parent directory.
      val plan = mutableListOf<WorkItem>()
      buildPlan(context, structure.allFiles, outputFolderUri, outputFolderName, statusCallback, plan)

      return@withContext executePlan(
         context = context,
         plan = plan,
         structure = structure,
         concurrency = concurrency,
         resultUri = outputFolderUri,
         progressCallback = progressCallback,
         statusCallback = statusCallback,
         fileEventCallback = fileEventCallback,
         cleanupOnFailure = { deleteFolder(context, outputFolderUri) }
      )
   }

   /**
    * Phase 2: run the prepared [plan] in parallel, aggregate the summary, and
    * stream progress. Shared by [processFolder] and [processCombined], which
    * differ only in how the output base and [plan] are laid out (folder mode
    * wraps everything in one new folder; combined plants the top level straight
    * into the output base). [resultUri] is returned on success and logged as the
    * output location; [cleanupOnFailure] removes any wrapper folder created up
    * front when the whole job fails (a no-op for combined, whose outputs are
    * spread across the shared base).
    */
   private suspend fun executePlan(
      context: Context,
      plan: List<WorkItem>,
      structure: FolderStructure,
      concurrency: Int,
      resultUri: Uri,
      progressCallback: (FolderProgressUpdate) -> Unit,
      statusCallback: NszConverter.StatusCallback?,
      fileEventCallback: ((FolderFileEvent) -> Unit)?,
      cleanupOnFailure: () -> Unit
   ): Result<Pair<Uri, FolderConversionSummary>> {
      val totalFileCount = countAllFiles(structure.allFiles)
      val results = mutableListOf<FileConversionResult>()

      try {
         statusCallback?.onStatus("INFO", "Starting file processing...")
         val processingStartTime = System.currentTimeMillis()

         // --- shared progress state, guarded by [lock] ---
         val lock = Any()
         val perFileDone = LongArray(plan.size)
         val fileTotals = LongArray(plan.size) { plan[it].size.coerceAtLeast(0L) }
         val active = HashMap<Int, ActiveFolderFile>()
         val overallThrottler = ProgressThrottler()
         var lastOverall = ConversionProgress(0L, structure.totalSize.coerceAtLeast(1L), 0.0)
         val processed = AtomicInteger(0)

         // Caller must hold [lock].
         fun emit() {
            val total = fileTotals.sum().coerceAtLeast(1L)
            val done = perFileDone.sum().coerceAtMost(total)
            overallThrottler.sample(done, total)?.let { lastOverall = it }
            val snapshot = active.entries.sortedBy { it.key }.map { it.value }
            progressCallback(
               FolderProgressUpdate(
                  overallProgress = lastOverall,
                  activeFiles = snapshot,
                  processedFiles = processed.get(),
                  totalFiles = totalFileCount
               )
            )
         }

         // Phase 2: process one WorkItem, run under the Semaphore below.
         suspend fun processOne(i: Int) {
            val item = plan[i]
            val fileSizeMB = item.size / 1024.0 / 1024.0
            val startTime = System.currentTimeMillis()
            val opType = when {
               item.isNsz -> FileOperationType.NSZ_CONVERSION
               item.isXcz -> FileOperationType.XCZ_CONVERSION
               else       -> FileOperationType.FILE_COPY
            }
            try {
               if (item.isNsz || item.isXcz) {
                  // NSZ → NSP, XCZ → XCI. Both decompress straight into the
                  // destination descriptor (no temp output + copy).
                  val outputName = item.name.substringBeforeLast('.') +
                     if (item.isXcz) ".xci" else ".nsp"
                  val tag = if (item.isXcz) "XCZ" else "NSZ"
                  statusCallback?.onStatus(tag, "Starting conversion: ${item.name} (%.2f MB)".format(fileSizeMB))
                  fileEventCallback?.invoke(FolderFileEvent(item.sourceUri, FileStatus.Converting))
                  val fileThrottler = ProgressThrottler()

                  val verify = convertDirect(
                     context, item.sourceUri, item.destParentUri, item.destRelativePath,
                     outputName, item.isXcz,
                     { done, total ->
                        fileThrottler.sample(done, total)?.let { p ->
                           synchronized(lock) {
                              if (p.totalBytes > 0L) fileTotals[i] = p.totalBytes
                              perFileDone[i] = p.doneBytes.coerceIn(0L, fileTotals[i])
                              active[i] = ActiveFolderFile(item.name, p)
                              emit()
                           }
                        }
                     },
                     statusCallback
                  )

                  val elapsedMs = System.currentTimeMillis() - startTime
                  // Uncompressed output size, as tracked from progress totals.
                  val unpackedBytes = synchronized(lock) { fileTotals[i] }.coerceAtLeast(item.size)
                  // Per-file speed over the uncompressed size, matching the
                  // single-files list (bytes produced ÷ time).
                  val unpackedMB = unpackedBytes / 1024.0 / 1024.0
                  val speedMBps = if (elapsedMs > 0) unpackedMB / (elapsedMs / 1000.0) else 0.0
                  statusCallback?.onStatus(tag, "Conversion completed: ${item.name} in ${elapsedMs / 1000}s (%.2f MB/s)".format(speedMBps))
                  fileEventCallback?.invoke(FolderFileEvent(
                     item.sourceUri, FileStatus.Completed, elapsedMs, speedMBps, unpackedBytes, verify
                  ))

                  synchronized(results) {
                     results.add(FileConversionResult.Success(
                        fileName = item.name,
                        outputName = outputName,
                        sizeBytes = item.size,
                        unpackedSizeBytes = unpackedBytes,
                        durationMs = elapsedMs,
                        operationType = opType
                     ))
                  }
               } else {
                  statusCallback?.onStatus("COPY", "Copying: ${item.name} (%.2f MB)".format(fileSizeMB))
                  fileEventCallback?.invoke(FolderFileEvent(item.sourceUri, FileStatus.Converting))
                  copyFile(context, item.sourceUri, item.destParentUri, item.destRelativePath, item.name, statusCallback)
                  val elapsedMs = System.currentTimeMillis() - startTime
                  val copyMB = item.size / 1024.0 / 1024.0
                  val copySpeedMBps = if (elapsedMs > 0) copyMB / (elapsedMs / 1000.0) else 0.0
                  statusCallback?.onStatus("COPY", "Copy completed: ${item.name} in ${elapsedMs}ms")
                  // Copies are tracked as file cards too: no unpacking, no verification.
                  fileEventCallback?.invoke(FolderFileEvent(
                     item.sourceUri, FileStatus.Completed, elapsedMs, copySpeedMBps, item.size, VerifyStatus.NOT_CHECKED
                  ))

                  synchronized(results) {
                     results.add(FileConversionResult.Success(
                        fileName = item.name,
                        outputName = item.name,
                        sizeBytes = item.size,
                        unpackedSizeBytes = item.size,
                        durationMs = elapsedMs,
                        operationType = opType
                     ))
                  }
               }
            } catch (e: NszConversionException) {
               statusCallback?.onStatus("ERROR", "Conversion failed: ${item.name} - ${e.message} (code: ${e.code})")
               fileEventCallback?.invoke(FolderFileEvent(item.sourceUri, FileStatus.Failed))
               synchronized(results) {
                  results.add(FileConversionResult.Failed(
                     fileName = item.name,
                     errorCode = e.code,
                     errorMessage = e.message ?: "Unknown error",
                     sizeBytes = item.size,
                     operationType = opType
                  ))
               }
            } catch (e: Exception) {
               statusCallback?.onStatus("ERROR", "Processing error: ${item.name} - ${e.message}")
               fileEventCallback?.invoke(FolderFileEvent(item.sourceUri, FileStatus.Failed))
               synchronized(results) {
                  results.add(FileConversionResult.Failed(
                     fileName = item.name,
                     errorCode = -999,
                     errorMessage = e.message ?: "Unknown error",
                     sizeBytes = item.size,
                     operationType = opType
                  ))
               }
            } finally {
               synchronized(lock) {
                  perFileDone[i] = fileTotals[i]
                  active.remove(i)
                  processed.incrementAndGet()
                  emit()
               }
            }
         }

         val sem = Semaphore(concurrency)
         coroutineScope {
            plan.indices.map { i ->
               async { sem.withPermit { processOne(i) } }
            }.awaitAll()
         }

         val totalTimeMs = System.currentTimeMillis() - processingStartTime
         val cumulativeBytesProcessed = fileTotals.sum()
         val avgSpeedMBps = if (totalTimeMs > 0) {
            (cumulativeBytesProcessed / 1024.0 / 1024.0) / (totalTimeMs / 1000.0)
         } else 0.0

         // Pin the overall bar to 100% (throttling can otherwise leave the last
         // emit a hair below full) and clear the per-file bars.
         val finalTotal = fileTotals.sum().coerceAtLeast(1L)
         progressCallback(
            FolderProgressUpdate(
               overallProgress = ConversionProgress(finalTotal, finalTotal, 0.0),
               activeFiles = emptyList(),
               processedFiles = totalFileCount,
               totalFiles = totalFileCount
            )
         )

         // Aggregate statistics by operation type.
         val nszResults = results.filter {
            (it is FileConversionResult.Success && it.operationType == FileOperationType.NSZ_CONVERSION) ||
            (it is FileConversionResult.Failed && it.operationType == FileOperationType.NSZ_CONVERSION)
         }
         val xczResults = results.filter {
            (it is FileConversionResult.Success && it.operationType == FileOperationType.XCZ_CONVERSION) ||
            (it is FileConversionResult.Failed && it.operationType == FileOperationType.XCZ_CONVERSION)
         }
         val copyResults = results.filter {
            (it is FileConversionResult.Success && it.operationType == FileOperationType.FILE_COPY) ||
            (it is FileConversionResult.Failed && it.operationType == FileOperationType.FILE_COPY)
         }

         val summary = FolderConversionSummary(
            totalFiles = totalFileCount,
            nszFilesProcessed = structure.nszFiles.size,
            successCount = results.count { it is FileConversionResult.Success },
            failedCount = results.count { it is FileConversionResult.Failed },
            skippedCount = results.count { it is FileConversionResult.Skipped },
            results = results.toList(),
            totalDurationMs = totalTimeMs,
            totalBytesProcessed = cumulativeBytesProcessed,
            nszSuccessCount = nszResults.count { it is FileConversionResult.Success },
            nszFailedCount = nszResults.count { it is FileConversionResult.Failed },
            xczSuccessCount = xczResults.count { it is FileConversionResult.Success },
            xczFailedCount = xczResults.count { it is FileConversionResult.Failed },
            xczFilesProcessed = structure.xczFiles.size,
            copySuccessCount = copyResults.count { it is FileConversionResult.Success },
            copyFailedCount = copyResults.count { it is FileConversionResult.Failed },
            copyFilesProcessed = copyResults.size
         )

         statusCallback?.onStatus("SUMMARY", "========================================")
         statusCallback?.onStatus("SUMMARY", "Conversion Summary")
         statusCallback?.onStatus("SUMMARY", "========================================")
         statusCallback?.onStatus("SUMMARY", "Total files: ${summary.totalFiles}")
         statusCallback?.onStatus("SUMMARY", "Successful: ${summary.successCount}")
         statusCallback?.onStatus("SUMMARY", "Failed: ${summary.failedCount}")
         statusCallback?.onStatus("SUMMARY", "")
         statusCallback?.onStatus("SUMMARY", "NSZ conversions: ${summary.nszSuccessCount}/${summary.nszFilesProcessed} (failed: ${summary.nszFailedCount})")
         statusCallback?.onStatus("SUMMARY", "Files copied: ${summary.copySuccessCount}/${summary.copyFilesProcessed} (failed: ${summary.copyFailedCount})")
         statusCallback?.onStatus("SUMMARY", "")
         statusCallback?.onStatus("SUMMARY", "Time: ${totalTimeMs / 1000}s")
         statusCallback?.onStatus("SUMMARY", "Average speed: %.2f MB/s".format(avgSpeedMBps))

         if (summary.failedCount > 0) {
            statusCallback?.onStatus("SUMMARY", "")
            statusCallback?.onStatus("SUMMARY", "Failed files:")
            results.filterIsInstance<FileConversionResult.Failed>().forEach { failed ->
               statusCallback?.onStatus("FAILED_FILE", "  ${failed.fileName}: ${failed.errorMessage} (code: ${failed.errorCode})")
            }
         }

         statusCallback?.onStatus("COMPLETE", "Result saved to: $resultUri")

         return if (summary.successCount > 0) {
            Result.success(Pair(resultUri, summary))
         } else {
            cleanupOnFailure()
            Result.failure(Exception("All files failed to process. Successful: 0, Errors: ${summary.failedCount}"))
         }
      } catch (e: NszConversionException) {
         statusCallback?.onStatus("ERROR", "NSZ conversion error: ${e.message} (code: ${e.code})")
         cleanupOnFailure()
         return Result.failure(e)
      } catch (e: Exception) {
         statusCallback?.onStatus("ERROR", "Critical processing error: ${e.message}")
         statusCallback?.onStatus("ERROR", "Stack: ${e.stackTraceToString().take(500)}")
         cleanupOnFailure()
         return Result.failure(e)
      }
   }

   /**
    * Combined-mode processing: unpack a synthetic [structure] whose top level is
    * the user's selection — standalone files plus one directory per selected
    * folder — straight into the output base. The base is the chosen
    * [outputBaseUri] (a SAF tree) or the public Downloads folder by default.
    *
    * There is no wrapper folder: standalone files land in the base root and each
    * selected folder is recreated in the base under its own name, gaining an
    * "_unpacked" (then "_unpacked_2", …) suffix only when a directory of that
    * name already exists there. Reuses [buildPlan]/[executePlan] for the work.
    */
   suspend fun processCombined(
      context: Context,
      structure: FolderStructure,
      outputBaseUri: Uri?,
      concurrency: Int,
      progressCallback: (FolderProgressUpdate) -> Unit,
      statusCallback: NszConverter.StatusCallback?,
      fileEventCallback: ((FolderFileEvent) -> Unit)? = null
   ): Result<Pair<Uri, FolderConversionSummary>> = withContext(Dispatchers.IO) {
      statusCallback?.onStatus("INFO", "Starting combined processing")
      statusCallback?.onStatus("INFO", "Top-level items: ${structure.allFiles.size}")
      statusCallback?.onStatus("INFO", "NSZ files to convert: ${structure.nszFiles.size}")
      statusCallback?.onStatus("INFO", "XCZ files to convert: ${structure.xczFiles.size}")
      statusCallback?.onStatus("INFO", "Total size: %.2f MB".format(structure.totalSize / 1024.0 / 1024.0))
      statusCallback?.onStatus("INFO", "Parallelism: $concurrency")

      // Resolve the output base. SAF → the tree's root document uri; otherwise the
      // public Downloads folder as a file:// uri (the app holds all-files access,
      // A14). Both route buildPlan/convertDirect/createSubFolder into a branch that
      // places the top level directly in the base (no wrapper, null relative path).
      val baseUri: Uri = if (outputBaseUri != null) {
         DocumentsContract.buildDocumentUriUsingTree(
            outputBaseUri, DocumentsContract.getTreeDocumentId(outputBaseUri)
         )
      } else {
         Uri.fromFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
      }

      // Give each top-level directory a collision-free name within the base before
      // planning (standalone files and nested directories are left untouched).
      val topLevel = resolveTopLevelNames(context, structure.allFiles, outputBaseUri, statusCallback)

      // Phase 1: create the output tree (folders live directly in the base) and a
      // flat plan; phase 2 (executePlan) runs it in parallel.
      val plan = mutableListOf<WorkItem>()
      buildPlan(context, topLevel, baseUri, null, statusCallback, plan)

      executePlan(
         context = context,
         plan = plan,
         structure = structure,
         concurrency = concurrency,
         resultUri = baseUri,
         progressCallback = progressCallback,
         statusCallback = statusCallback,
         fileEventCallback = fileEventCallback,
         // Outputs are spread across the shared base (no wrapper), so there is
         // nothing safe to bulk-delete when the whole job fails.
         cleanupOnFailure = { }
      )
   }

   /**
    * Returns [nodes] with each top-level directory renamed so it does not collide
    * with an existing directory in the output base: the first free of "<name>",
    * "<name>_unpacked", "<name>_unpacked_2", … is used. Files and nested
    * directories are returned unchanged. [outputBaseUri] is the SAF tree, or null
    * for the public Downloads folder; existence is checked per base kind.
    */
   private fun resolveTopLevelNames(
      context: Context,
      nodes: List<FileNode>,
      outputBaseUri: Uri?,
      statusCallback: NszConverter.StatusCallback?
   ): List<FileNode> {
      // Names claimed within this run, so two selected folders sharing a name don't
      // both map onto the same output directory.
      val taken = mutableSetOf<String>()
      return nodes.map { node ->
         when (node) {
            is FileNode.Directory -> {
               val name = pickFreeDirName(context, node.name, outputBaseUri, taken)
               taken.add(name.lowercase())
               if (name != node.name) {
                  statusCallback?.onStatus("FOLDER", "Renaming to avoid collision: ${node.name} -> $name")
               }
               node.copy(name = name)
            }
            is FileNode.File -> node
         }
      }
   }

   private fun pickFreeDirName(
      context: Context,
      original: String,
      outputBaseUri: Uri?,
      taken: Set<String>
   ): String {
      fun exists(candidate: String): Boolean =
         candidate.lowercase() in taken || dirExistsInBase(context, outputBaseUri, candidate)
      if (!exists(original)) return original
      var candidate = "${original}_unpacked"
      var index = 2
      while (exists(candidate)) {
         candidate = "${original}_unpacked_$index"
         index++
      }
      return candidate
   }

   private fun dirExistsInBase(context: Context, outputBaseUri: Uri?, name: String): Boolean {
      return if (outputBaseUri != null) {
         outputNameExistsInSaf(context, outputBaseUri, name)
      } else {
         val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
         File(downloads, name).isDirectory
      }
   }

   /**
    * Recursively creates the output directory tree and appends a flat [WorkItem]
    * for every file, with its resolved output parent already in hand.
    */
   private fun buildPlan(
      context: Context,
      nodes: List<FileNode>,
      destParentUri: Uri,
      destRelativePath: String?,
      statusCallback: NszConverter.StatusCallback?,
      out: MutableList<WorkItem>
   ) {
      for (node in nodes) {
         when (node) {
            is FileNode.File -> {
               out.add(
                  WorkItem(
                     sourceUri = node.uri,
                     name = node.name,
                     destParentUri = destParentUri,
                     destRelativePath = destRelativePath,
                     isNsz = node.isNsz,
                     isXcz = node.isXcz,
                     size = getUriSize(context, node.uri).coerceAtLeast(0L)
                  )
               )
            }
            is FileNode.Directory -> {
               statusCallback?.onStatus("FOLDER", "Creating subfolder: ${node.name} (${node.children.size} items)")
               val subFolder = createSubFolder(context, destParentUri, destRelativePath, node.name, statusCallback)
               if (subFolder != null) {
                  buildPlan(context, node.children, subFolder.first, subFolder.second, statusCallback, out)
               } else {
                  statusCallback?.onStatus("ERROR", "Failed to create subfolder: ${node.name}")
               }
            }
         }
      }
   }

   /**
    * Converts a single compressed container directly into the output file's
    * descriptor (no temp-output + copy step), reading the source through its
    * descriptor with `fd:N` (no input copy). [isXcz] selects XCZ → XCI
    * (`nativeConvertXcz`) over NSZ → NSP (`nativeConvert`). Mirrors
    * [NszConverter.convert], including the FUSE temp-copy fallback. The verify
    * verdict is derived from the engine's inline hashing tags (VERIFIED/CORRUPTED)
    * emitted during decompression — no separate output re-read pass (which was
    * expensive on write-bound storage and stole bandwidth from parallel writes).
    * A mismatch is non-fatal: the output is kept. Verification runs only when it
    * was enabled globally via nativeSetVerification.
    * Throws [NszConversionException] only on an actual conversion failure.
    */
   private fun convertDirect(
      context: Context,
      sourceUri: Uri,
      destParentUri: Uri,
      destRelativePath: String?,
      outputName: String,
      isXcz: Boolean,
      onProgress: (Long, Long) -> Unit,
      statusCallback: NszConverter.StatusCallback?
   ): VerifyStatus {
      val tag = if (isXcz) "XCZ" else "NSZ"
      var destUri: Uri? = null      // content uri to finalize / clean up
      var destFile: File? = null    // file:// target to clean up on failure
      var outPfd: ParcelFileDescriptor? = null
      var inputPfd: ParcelFileDescriptor? = null
      var resolvedInput: ResolvedInputFile? = null
      var mediaStorePending = false
      // Verify verdict from the engine's inline hashing tags (see below).
      var sawVerified = false
      var sawCorrupted = false

      try {
         // --- output target: a real fd we hand to native to write into ---
         val outputPath: String = when {
            destParentUri.scheme == "file" -> {
               val f = File(File(destParentUri.path!!), outputName)
               destFile = f
               f.absolutePath
            }
            DocumentsContract.isDocumentUri(context, destParentUri) -> {
               val u = DocumentsContract.createDocument(
                  context.contentResolver, destParentUri, "application/octet-stream", outputName
               ) ?: throw NszConversionException(-2, "Cannot create SAF output file: $outputName")
               destUri = u
               val pfd = context.contentResolver.openFileDescriptor(u, "rw")
                  ?: throw NszConversionException(-2, "Cannot open SAF output descriptor: $outputName")
               outPfd = pfd
               "/proc/self/fd/${pfd.fd}"
            }
            else -> {
               val cv = ContentValues().apply {
                  put(MediaStore.Downloads.DISPLAY_NAME, outputName)
                  put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                  put(MediaStore.Downloads.IS_PENDING, 1)
                  val rel = resolveRelativePath(context, destParentUri, destRelativePath)
                  if (rel != null) put(MediaStore.Downloads.RELATIVE_PATH, "Download/$rel")
               }
               val u = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                  ?: throw NszConversionException(-2, "Cannot create output file: $outputName")
               destUri = u
               mediaStorePending = true
               val pfd = context.contentResolver.openFileDescriptor(u, "rw")
                  ?: throw NszConversionException(-2, "Cannot open output descriptor: $outputName")
               outPfd = pfd
               "/proc/self/fd/${pfd.fd}"
            }
         }

         // --- input path: prefer fd:N (no copy), fall back to a temp copy ---
         val inputPath: String = if (sourceUri.scheme == "file") {
            sourceUri.path!!
         } else {
            val p = context.contentResolver.openFileDescriptor(sourceUri, "r")
            if (p != null && p.statSize >= 0L) {
               inputPfd = p
               "fd:${p.fd}"
            } else {
               p?.close()
               val r = resolveToFilePath(context, sourceUri, statusCallback)
               resolvedInput = r
               r.file.absolutePath
            }
         }

         val cb = object : NszConverter.ProgressCallback {
            override fun onProgress(done: Long, total: Long) = onProgress(done, total)
         }

         // Intercept the engine's inline verify tags, forwarding all status
         // through unchanged (mirrors NszConverter.VerifyTracker).
         val trackCb = object : NszConverter.StatusCallback {
            override fun onStatus(t: String, msg: String) {
               when (t) {
                  "VERIFIED" -> sawVerified = true
                  "CORRUPTED" -> sawCorrupted = true
               }
               statusCallback?.onStatus(t, msg)
            }
         }

         fun runNative(input: String): Int =
            if (isXcz) NszConverter.nativeConvertXcz(input, outputPath, cb, trackCb)
            else       NszConverter.nativeConvert(input, outputPath, cb, trackCb)

         var result = runNative(inputPath)

         // FUSE fallback: a descriptor whose /proc/self/fd path can't be re-opened
         // by native code surfaces as an input/parse error. Retry with a temp copy.
         if (result != NszConverter.OK && inputPfd != null &&
            (result == NszConverter.ERR_OPEN_INPUT || result == NszConverter.ERR_INVALID_PFS0 ||
               result == NszConverter.ERR_INVALID_NCZ || result == NszConverter.ERR_IO)) {
            statusCallback?.onStatus(tag, "Direct read failed (code $result), copying to cache and retrying: $outputName")
            runCatching { inputPfd?.close() }
            inputPfd = null
            val r = resolveToFilePath(context, sourceUri, statusCallback)
            resolvedInput = r
            result = runNative(r.file.absolutePath)
         }

         if (result != NszConverter.OK) {
            throw NszConversionException(result, NszConverter.nativeErrorString(result))
         }

         // The output is fully written. Close the write descriptor now so the
         // file is committed/flushed before we mark it not-pending. (This also
         // preserved the old reopen-to-verify from racing on FUSE; the verify
         // re-read is gone, but flushing before finalize is still correct.)
         runCatching { outPfd?.close() }
         outPfd = null

         if (mediaStorePending && destUri != null) {
            context.contentResolver.update(
               destUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null
            )
         }
      } catch (e: Exception) {
         // Drop the partial output before bubbling the error up.
         runCatching { outPfd?.close() }
         outPfd = null
         destUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
         destFile?.let { runCatching { if (it.exists()) it.delete() } }
         throw e
      } finally {
         runCatching { outPfd?.close() }
         runCatching { inputPfd?.close() }
         resolvedInput?.deleteIfTemp(statusCallback)
      }

      // Verify verdict from the engine's inline hashing tags — no output re-read.
      // NOT_CHECKED when verification is off or nothing was hashed.
      return when {
         sawCorrupted -> VerifyStatus.FAILED
         sawVerified -> VerifyStatus.CHECKED
         else -> VerifyStatus.NOT_CHECKED
      }
   }

   private fun createOutputFolder(context: Context, name: String): Uri? {
      return try {
         val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 0)
         }
         context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            cv
         )
      } catch (e: Exception) {
         try {
            val downloadsDir = File(context.getExternalFilesDir(null), "Downloads")
            downloadsDir.mkdirs()
            val outputDir = File(downloadsDir, name)
            outputDir.mkdirs()
            Uri.fromFile(outputDir)
         } catch (e2: Exception) {
            null
         }
      }
   }

   private fun createSubFolder(
      context: Context,
      parentUri: Uri,
      parentRelativePath: String?,
      name: String,
      statusCallback: NszConverter.StatusCallback? = null
   ): Pair<Uri, String?>? {
      return try {
         val result = when {
            parentUri.scheme == "file" -> {
               val parentFile = File(parentUri.path!!)
               val subFolder = File(parentFile, name)
               subFolder.mkdirs()
               Pair(Uri.fromFile(subFolder), null)
            }
            DocumentsContract.isDocumentUri(context, parentUri) -> {
               val subUri = DocumentsContract.createDocument(
                  context.contentResolver, parentUri,
                  DocumentsContract.Document.MIME_TYPE_DIR, name
               ) ?: return null
               Pair(subUri, null)
            }
            else -> Pair(parentUri, buildChildRelativePath(parentRelativePath, name))
         }
         statusCallback?.onStatus("FOLDER", "Subfolder created: $name")
         result
      } catch (e: Exception) {
         statusCallback?.onStatus("ERROR", "Error creating subfolder '$name': ${e.message}")
         null
      }
   }

   private fun copyFile(
      context: Context,
      sourceUri: Uri,
      destParentUri: Uri,
      destRelativePath: String?,
      fileName: String,
      statusCallback: NszConverter.StatusCallback? = null
   ) {
      try {
         when {
            destParentUri.scheme == "file" -> {
               val destFile = File(File(destParentUri.path!!), fileName)
               statusCallback?.onStatus("COPY", "Copying file to: ${destFile.absolutePath}")

               context.contentResolver.openInputStream(sourceUri)?.use { input ->
                  destFile.outputStream().use { output ->
                     input.copyTo(output)
                  }
               }
            }
            DocumentsContract.isDocumentUri(context, destParentUri) -> {
               statusCallback?.onStatus("COPY", "Creating file via SAF: $fileName")
               val destUri = DocumentsContract.createDocument(
                  context.contentResolver, destParentUri, "application/octet-stream", fileName
               ) ?: throw Exception("Cannot create SAF file: $fileName")

               context.contentResolver.openInputStream(sourceUri)?.use { input ->
                  context.contentResolver.openOutputStream(destUri)?.use { output ->
                     input.copyTo(output)
                  }
               }
            }
            else -> {
               statusCallback?.onStatus("COPY", "Creating file via MediaStore: $fileName")

               val cv = ContentValues().apply {
                  put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                  put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                  put(MediaStore.Downloads.IS_PENDING, 1)

                  val relativePath = resolveRelativePath(context, destParentUri, destRelativePath)
                  if (relativePath != null) {
                     put(MediaStore.Downloads.RELATIVE_PATH, "Download/$relativePath")
                  }
               }

               val destUri = context.contentResolver.insert(
                  MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                  cv
               ) ?: throw Exception("Cannot create destination file: $fileName")

               try {
                  context.contentResolver.openInputStream(sourceUri)?.use { input ->
                     context.contentResolver.openOutputStream(destUri)?.use { output ->
                        input.copyTo(output)
                     }
                  }

                  val updateCv = ContentValues().apply {
                     put(MediaStore.Downloads.IS_PENDING, 0)
                  }
                  context.contentResolver.update(destUri, updateCv, null, null)

               } catch (e: Exception) {
                  context.contentResolver.delete(destUri, null, null)
                  throw e
               }
            }
         }
         statusCallback?.onStatus("COPY", "File copied successfully: $fileName")
      } catch (e: Exception) {
         statusCallback?.onStatus("ERROR", "Error copying file '$fileName': ${e.message}")
         throw e
      }
   }

   private fun deleteFolder(context: Context, folderUri: Uri) {
      try {
         if (folderUri.scheme == "file") {
            File(folderUri.path!!).deleteRecursively()
         } else {
            DocumentsContract.deleteDocument(context.contentResolver, folderUri)
         }
      } catch (e: Exception) {
         // Ignore deletion errors
      }
   }

   private fun generateOutputFolderName(context: Context, rootUri: Uri, outputBaseUri: Uri? = null): String {
      val originalName = getSourceFolderName(context, rootUri)
         .takeIf { it.isNotBlank() }
         ?: "AndroNSZ"
      val baseName = "${originalName}_unpacked"

      var candidate = baseName
      var index = 2
      while (if (outputBaseUri != null) outputNameExistsInSaf(context, outputBaseUri, candidate)
         else outputNameExists(context, candidate)) {
         candidate = "${baseName}_$index"
         index++
      }

      return candidate
   }

   private fun createOutputFolderInSaf(context: Context, treeUri: Uri, name: String): Uri? {
      return try {
         val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
         )
         DocumentsContract.createDocument(
            context.contentResolver, treeDocUri, DocumentsContract.Document.MIME_TYPE_DIR, name
         )
      } catch (e: Exception) {
         null
      }
   }

   private fun outputNameExistsInSaf(context: Context, treeUri: Uri, name: String): Boolean {
      val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
         treeUri, DocumentsContract.getTreeDocumentId(treeUri)
      )
      return try {
         context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
         )?.use { cursor ->
            while (cursor.moveToNext()) {
               val col = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
               if (col >= 0 && cursor.getString(col) == name) return@use true
            }
            false
         } ?: false
      } catch (e: Exception) {
         false
      }
   }

   private fun getSourceFolderName(context: Context, rootUri: Uri): String {
      val queryUri = toDocumentUri(rootUri)

      context.contentResolver.query(
         queryUri,
         arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
         null,
         null,
         null
      )?.use { cursor ->
         if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
               return cursor.getString(nameIndex)
            }
         }
      }

      return rootUri.lastPathSegment
         ?.substringAfterLast('/')
         ?.substringAfterLast(':')
         ?.ifBlank { "AndroNSZ" }
         ?: "AndroNSZ"
   }

   private fun toDocumentUri(uri: Uri): Uri {
      if (uri.scheme != "content") return uri

      return if (DocumentsContract.isTreeUri(uri)) {
         DocumentsContract.buildDocumentUriUsingTree(
            uri,
            DocumentsContract.getTreeDocumentId(uri)
         )
      } else {
         uri
      }
   }

   private fun outputNameExists(context: Context, name: String): Boolean {
      val downloadsDir = File(context.getExternalFilesDir(null), "Downloads")
      return File(downloadsDir, name).exists()
   }

   private fun buildChildRelativePath(parentRelativePath: String?, name: String): String {
      return parentRelativePath
         ?.takeIf { it.isNotBlank() }
         ?.let { "$it/$name" }
         ?: name
   }

   private fun resolveRelativePath(context: Context, destParentUri: Uri, destRelativePath: String?): String? {
      return destRelativePath?.takeIf { it.isNotBlank() } ?: getRelativePathFromUri(context, destParentUri)
   }

   private fun getRelativePathFromUri(context: Context, uri: Uri): String? {
      if (uri.scheme != "content") return null

      return try {
         context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            null,
            null,
            null
         )?.use { cursor ->
            if (cursor.moveToFirst()) {
               val nameIndex = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
               if (nameIndex >= 0) {
                  cursor.getString(nameIndex)
               } else null
            } else null
         }
      } catch (e: Exception) {
         null
      }
   }
}
