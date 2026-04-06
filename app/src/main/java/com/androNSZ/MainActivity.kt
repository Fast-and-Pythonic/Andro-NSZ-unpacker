package com.androNSZ

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androNSZ.ui.theme.AndroNSZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LogEntry(val tag: String, val message: String)

private val compactToggleButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

/* ── Models ──────────────────────────────────────────────────────── */

sealed class Screen {
   object ModeSelection : Screen()
   object Conversion : Screen()
}

sealed class ConversionMode {
   object None : ConversionMode()
   data class SingleFiles(val files: List<FileEntry>) : ConversionMode()
   data class FolderMode(val folderUri: Uri, val structure: FolderStructure?) : ConversionMode()
}

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

data class FolderStructure(
   val rootUri: Uri,
   val nszFiles: List<Uri>,
   val allFiles: List<FileNode>,
   val totalSize: Long
)

sealed class FileNode {
   data class File(val uri: Uri, val name: String, val isNsz: Boolean) : FileNode()
   data class Directory(val name: String, val children: List<FileNode>) : FileNode()
}

/* ── ViewModel ───────────────────────────────────────────────────── */

class MainViewModel : ViewModel() {
   
   // Navigation & Mode
   var currentScreen by mutableStateOf<Screen>(Screen.ModeSelection)
   var conversionMode by mutableStateOf<ConversionMode>(ConversionMode.None)
   
   // Single file mode (legacy)
   var selectedUri   by mutableStateOf<Uri?>(null)
   var selectedName  by mutableStateOf<String?>(null)
   
   // Batch files mode
   val fileQueue = mutableStateListOf<FileEntry>()
   var currentFileIndex by mutableStateOf(0)
   var batchOverallProgress by mutableStateOf<ConversionProgress?>(null)
   var batchCurrentFileName by mutableStateOf<String?>(null)
   var batchProcessedFiles by mutableStateOf(0)
   var batchTotalFiles by mutableStateOf(0)
   
   // Folder mode
   var folderStructure by mutableStateOf<FolderStructure?>(null)
   private var folderLogWriter: FolderLogWriter? = null
   var folderLogPath by mutableStateOf<String?>(null)
   var folderOverallProgress by mutableStateOf<ConversionProgress?>(null)
   var folderCurrentFileProgress by mutableStateOf<ConversionProgress?>(null)
   var folderCurrentFileName by mutableStateOf<String?>(null)
   var folderProcessedFiles by mutableStateOf(0)
   var folderTotalFiles by mutableStateOf(0)
   
   // Conversion state
   var isConverting  by mutableStateOf(false)
   var progress      by mutableStateOf<ConversionProgress?>(null)
   var statusMessage by mutableStateOf<String?>(null)
   var isSuccess     by mutableStateOf(false)
   var keysInstalled by mutableStateOf(false)
   val statusLog = mutableStateListOf<LogEntry>()
   
   fun checkKeys(context: android.content.Context) {
      keysInstalled = KeysManager.isInstalled(context)
   }
   
   fun installKeys(context: android.content.Context, uri: Uri) {
      viewModelScope.launch {
         KeysManager.installFromUri(context, uri)
         keysInstalled = KeysManager.isInstalled(context)
      }
   }
   
   fun pickFile(uri: Uri, displayName: String) {
      selectedUri   = uri
      selectedName  = displayName
      statusMessage = null
      isSuccess     = false
      progress      = null
      statusLog.clear()
   }
   
   fun startConversion(context: android.content.Context) {
      val uri = selectedUri ?: return
      isConverting  = true
      isSuccess     = false
      statusMessage = null
      progress      = ConversionProgress(0L, 0L, 0.0)
      statusLog.clear()
      
      val headerKey = KeysParser.parseHeaderKey(KeysManager.keysFile(context))
      
      val statusCb = object : NszConverter.StatusCallback {
         override fun onStatus(tag: String, msg: String) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
               statusLog.add(LogEntry(tag, msg.trim()))
            }
         }
      }
      
      viewModelScope.launch {
         NszConverter.convert(context, uri, headerKey, statusCb)
            .catch { e ->
               isConverting = false
               withContext(Dispatchers.IO) { TempFileManager.cleanupManagedCache(context) }
               val logPath = NszConverter.lastDebugLogPath
               val logSuffix = if (logPath != null) "\nDebug log: $logPath" else ""
               statusMessage = when (e) {
                  is CancelledException     -> "Cancelled.$logSuffix"
                  is NszConversionException -> "Error: ${e.message}$logSuffix"
                  else                      -> "Unexpected error: ${e.message}$logSuffix"
               }
            }
            .collect { p ->
               progress = p
            }
         if (isConverting) {
            isConverting = false
            withContext(Dispatchers.IO) { TempFileManager.cleanupManagedCache(context) }
            val logPath      = NszConverter.lastDebugLogPath
            val logSuffix    = if (logPath != null) "\nDebug log: $logPath" else ""
            val verifyError   = NszConverter.lastVerifyError
            val verifySkipped = NszConverter.lastVerifySkipped
            
            isSuccess     = true
            statusMessage = when {
               verifyError != null ->
                  "Conversion complete, but NSP verification failed:\n$verifyError$logSuffix"
                     .also { isSuccess = false }
               verifySkipped ->
                  "Done! Saved to Downloads.\n(header_key not found in prod.keys — verification skipped)$logSuffix"
               else ->
                  "Done! NSP verified. Saved to Downloads.$logSuffix"
            }
         }
      }
   }
   
   fun cancel() {
      NszConverter.cancel()
   }
   
   /* ── New mode management methods ────────────────────────────── */
   
   fun selectMode(mode: ConversionMode) {
      conversionMode = mode
   }
   
   fun addFilesToQueue(files: List<FileEntry>) {
      fileQueue.addAll(files)
   }
   
   fun removeFileFromQueue(index: Int) {
      if (index in fileQueue.indices) {
         fileQueue.removeAt(index)
      }
   }
   
   fun selectFolder(context: android.content.Context, uri: Uri) {
      viewModelScope.launch {
         try {
            statusMessage = "Сканирование папки..."
            statusLog.clear()
            
            // Создать writer для логов сканирования
            folderLogWriter = FolderLogWriter(context)
            folderLogPath = folderLogWriter?.logFilePath
            
            val statusCb = object : NszConverter.StatusCallback {
               override fun onStatus(tag: String, msg: String) {
                  viewModelScope.launch(Dispatchers.Main.immediate) {
                     statusLog.add(LogEntry(tag, msg.trim()))
                  }
                  // Записать в файл
                  viewModelScope.launch(Dispatchers.IO) {
                     folderLogWriter?.writeLog(tag, msg.trim())
                  }
               }
            }
            
            val structure = FolderScanner.scanFolder(context, uri, statusCb)
            folderStructure = structure
            conversionMode = ConversionMode.FolderMode(uri, structure)
            statusMessage = null
         } catch (e: Exception) {
            statusMessage = "Ошибка сканирования: ${e.message}"
            viewModelScope.launch(Dispatchers.IO) {
               folderLogWriter?.writeLog("ERROR", "Ошибка сканирования: ${e.message}")
               folderLogWriter?.close()
            }
         }
      }
   }
   
   fun resetConversionState() {
      conversionMode = ConversionMode.None
      fileQueue.clear()
      folderStructure = null
      selectedUri = null
      selectedName = null
      isConverting = false
      progress = null
      batchOverallProgress = null
      batchCurrentFileName = null
      batchProcessedFiles = 0
      batchTotalFiles = 0
      folderOverallProgress = null
      folderCurrentFileProgress = null
      folderCurrentFileName = null
      folderProcessedFiles = 0
      folderTotalFiles = 0
      statusMessage = null
      isSuccess = false
      statusLog.clear()
      currentFileIndex = 0
   }
   
   fun startBatchConversion(context: android.content.Context) {
      if (fileQueue.isEmpty()) return
      
      isConverting = true
      currentFileIndex = 0
      progress = null
      batchCurrentFileName = null
      batchProcessedFiles = 0
      batchTotalFiles = fileQueue.size
      statusLog.clear()
      statusMessage = null
      isSuccess = false
      
      val headerKey = KeysParser.parseHeaderKey(KeysManager.keysFile(context))
      
      val statusCb = object : NszConverter.StatusCallback {
         override fun onStatus(tag: String, msg: String) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
               statusLog.add(LogEntry(tag, msg.trim()))
            }
         }
      }
      
      viewModelScope.launch {
         val fileSizes = fileQueue.map { getUriSize(context, it.uri).coerceAtLeast(0L) }
         val estimatedFileTotals = fileSizes.toMutableList()
         val totalBytes = estimatedFileTotals.sum().coerceAtLeast(0L)
         batchOverallProgress = if (fileQueue.size > 1 && totalBytes > 0L) {
            ConversionProgress(0L, totalBytes, 0.0)
         } else {
            null
         }
         var completedBytes = 0L
         var lastOverallBytes = 0L
         var lastOverallTimeMs = System.currentTimeMillis()

         for (i in fileQueue.indices) {
            currentFileIndex = i
            val file = fileQueue[i]
            val fileSize = fileSizes.getOrElse(i) { 0L }
            var currentFileTotal = estimatedFileTotals.getOrElse(i) { fileSize }
            batchCurrentFileName = file.displayName
            
            fileQueue[i] = file.copy(status = FileStatus.Converting)
            
            try {
               NszConverter.convert(context, file.uri, headerKey, statusCb)
                  .catch { e ->
                     fileQueue[i] = file.copy(status = FileStatus.Failed)
                     statusLog.add(LogEntry("ERROR", "${file.displayName}: ${e.message}"))
                  }
                  .collect { p ->
                     progress = p
                     if (batchOverallProgress != null) {
                        val reportedTotal = p.totalBytes.coerceAtLeast(0L)
                        if (reportedTotal > 0L && reportedTotal != currentFileTotal) {
                           currentFileTotal = reportedTotal
                           estimatedFileTotals[i] = reportedTotal
                        }
                        val overallTotal = estimatedFileTotals.sum().coerceAtLeast(0L)
                        val currentDone = p.doneBytes.coerceAtLeast(0L).coerceAtMost(currentFileTotal)
                        val overallDone = (completedBytes + currentDone)
                           .coerceAtMost(overallTotal)
                        val now = System.currentTimeMillis()
                        val elapsedSec = (now - lastOverallTimeMs).coerceAtLeast(1L) / 1000.0
                        val speed = if (elapsedSec > 0) {
                           (overallDone - lastOverallBytes).toDouble() / 1024 / 1024 / elapsedSec
                        } else {
                           0.0
                        }
                        lastOverallBytes = overallDone
                        lastOverallTimeMs = now
                        batchOverallProgress = ConversionProgress(
                           doneBytes = overallDone,
                           totalBytes = overallTotal,
                           speedMBps = speed
                        )
                     }
                  }
               
               fileQueue[i] = file.copy(status = FileStatus.Completed)
               
            } catch (e: Exception) {
               fileQueue[i] = file.copy(status = FileStatus.Failed)
               statusLog.add(LogEntry("ERROR", "${file.displayName}: ${e.message}"))
            } finally {
               completedBytes += currentFileTotal
               batchProcessedFiles = i + 1
               if (batchOverallProgress != null) {
                  val overallTotal = estimatedFileTotals.sum().coerceAtLeast(0L)
                  batchOverallProgress = ConversionProgress(
                     doneBytes = completedBytes.coerceAtMost(overallTotal),
                     totalBytes = overallTotal,
                     speedMBps = batchOverallProgress!!.speedMBps
                  )
               }
               batchCurrentFileName = null
               withContext(Dispatchers.IO) { TempFileManager.cleanupManagedCache(context) }
            }
         }
         
         isConverting = false
         val completed = fileQueue.count { it.status == FileStatus.Completed }
         statusMessage = "Обработано $completed из ${fileQueue.size} файлов"
         isSuccess = completed == fileQueue.size
      }
   }
   
   fun startFolderConversion(context: android.content.Context) {
      val structure = folderStructure ?: return
      
      isConverting = true
      statusLog.clear()
      statusMessage = null
      isSuccess = false
      progress = null
      folderOverallProgress = ConversionProgress(0L, structure.totalSize, 0.0)
      folderCurrentFileProgress = null
      folderCurrentFileName = null
      folderProcessedFiles = 0
      folderTotalFiles = countAllFiles(structure.allFiles)
      
      // Создать новый writer для логов обработки
      folderLogWriter = FolderLogWriter(context)
      folderLogPath = folderLogWriter?.logFilePath
      
      val headerKey = KeysParser.parseHeaderKey(KeysManager.keysFile(context))
      
      val statusCb = object : NszConverter.StatusCallback {
         override fun onStatus(tag: String, msg: String) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
               statusLog.add(LogEntry(tag, msg.trim()))
            }
            // Записать в файл
            viewModelScope.launch(Dispatchers.IO) {
               folderLogWriter?.writeLog(tag, msg.trim())
            }
         }
      }
      
      viewModelScope.launch {
         try {
            val result = FolderProcessor.processFolder(
               context,
               structure,
               headerKey,
               { update ->
                  folderOverallProgress = update.overallProgress
                  folderCurrentFileProgress = update.currentFileProgress
                  folderCurrentFileName = update.currentFileName
                  folderProcessedFiles = update.processedFiles
                  folderTotalFiles = update.totalFiles
               },
               statusCb
            )
            
            isConverting = false
            
            // Закрыть лог-файл
            withContext(Dispatchers.IO) {
               folderLogWriter?.close()
            }
            
            val logPathMsg = if (folderLogPath != null) "\nЛог: $folderLogPath" else ""
            
            result.onSuccess { (outputUri, summary) ->
               val successRate = if (summary.nszFilesProcessed > 0) {
                  (summary.successCount * 100) / summary.nszFilesProcessed
               } else 100
               
               // Consider it a success if at least 50% succeeded
               isSuccess = summary.successCount > 0 && successRate >= 50
               
               statusMessage = buildString {
                  appendLine("Папка обработана!")
                  appendLine()
                  appendLine("Успешно: ${summary.successCount} из ${summary.nszFilesProcessed} NSZ файлов (${successRate}%)")
                  
                  if (summary.failedCount > 0) {
                     appendLine("Ошибок: ${summary.failedCount} файл(ов)")
                     appendLine("Подробности в логе")
                  }
                  
                  appendLine()
                  appendLine("Сохранено в Downloads")
                  append(logPathMsg)
               }
            }.onFailure { e ->
               isSuccess = false
               statusMessage = "Ошибка обработки папки: ${e.message}$logPathMsg"
            }
            
         } catch (e: Exception) {
            isConverting = false
            isSuccess = false
            
            // Закрыть лог-файл при ошибке
            withContext(Dispatchers.IO) {
               folderLogWriter?.writeLog("ERROR", "Критическая ошибка: ${e.message}")
               folderLogWriter?.close()
            }
            
            val logPathMsg = if (folderLogPath != null) "\nЛог: $folderLogPath" else ""
            statusMessage = "Ошибка: ${e.message}$logPathMsg"
         } finally {
            withContext(Dispatchers.IO) { TempFileManager.cleanupManagedCache(context) }
         }
      }
   }
}

/* ── Single Files Mode UI ────────────────────────────────────────── */

@Composable
fun SingleFilesUI(vm: MainViewModel, padding: PaddingValues) {
   val context = LocalContext.current
   
   val filePicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument()
   ) { uri ->
      if (uri != null) {
         val name = resolveDisplayName(context, uri)
         val size = getUriSize(context, uri)
         vm.addFilesToQueue(listOf(FileEntry(uri, name, size)))
      }
   }
   
   Column(
      modifier = Modifier
         .fillMaxSize()
         .padding(padding)
         .padding(all = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
   ) {
      // Кнопка добавления файлов
      Button(
         onClick = { filePicker.launch(arrayOf("*/*")) },
         enabled = !vm.isConverting,
         modifier = Modifier.fillMaxWidth()
      ) {
         Icon(Icons.Filled.Add, null)
         Spacer(Modifier.width(8.dp))
         Text("Добавить файлы")
      }
      
      // Список файлов в очереди
      if (vm.fileQueue.isNotEmpty()) {
         Text(
            text = "Файлов в очереди: ${vm.fileQueue.size}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
         )
         
         LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
         ) {
            items(vm.fileQueue.size) { index ->
               FileQueueItem(
                  file = vm.fileQueue[index],
                  onRemove = { vm.removeFileFromQueue(index) },
                  enabled = !vm.isConverting
               )
            }
         }
      } else {
         // Пустое состояние
         Box(
            modifier = Modifier
               .weight(1f)
               .fillMaxWidth(),
            contentAlignment = Alignment.Center
         ) {
            Text(
               text = "Нажмите \"Добавить файлы\" чтобы начать",
               style = MaterialTheme.typography.bodyLarge,
               color = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }
      }
      
      // Кнопка старта конверсии
      Button(
         onClick = { vm.startBatchConversion(context) },
         enabled = vm.fileQueue.isNotEmpty() && !vm.isConverting,
         modifier = Modifier.fillMaxWidth()
      ) {
         Text("Конвертировать ${vm.fileQueue.size} файл(ов)")
      }
      
      // Прогресс и логи
      if (vm.isConverting || vm.progress != null) {
         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier.padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
               Text(
                  text = "Файл ${vm.currentFileIndex + 1} из ${vm.fileQueue.size}",
                  style = MaterialTheme.typography.titleMedium
               )

               val isMultiFile = vm.fileQueue.size > 1
               val overall = vm.batchOverallProgress
               if (isMultiFile && overall != null) {
                  LinearProgressIndicator(
                     progress = { overall.percent },
                     modifier = Modifier.fillMaxWidth()
                  )
                  Row(
                     modifier = Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                     Text(
                        text = "Файлы: ${vm.batchProcessedFiles} / ${vm.batchTotalFiles}",
                        style = MaterialTheme.typography.bodySmall
                     )
                     Text(
                        text = "%.1f%%".format(overall.percent * 100f),
                        style = MaterialTheme.typography.bodySmall
                     )
                     Text(
                        text = "%.1f MB/s".format(overall.speedMBps),
                        style = MaterialTheme.typography.bodySmall
                     )
                  }
                  Text(
                     text = "${fmtBytes(overall.doneBytes)} / ${fmtBytes(overall.totalBytes)}",
                     style = MaterialTheme.typography.bodySmall
                  )
               }

               val p = vm.progress
               if (p != null) {
                  if (isMultiFile) {
                     Spacer(Modifier.height(4.dp))
                     Text(
                        text = vm.batchCurrentFileName ?: "Текущий файл",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                     )
                  }
                  LinearProgressIndicator(
                     progress = { p.percent },
                     modifier = Modifier.fillMaxWidth()
                  )
                  Row(
                     modifier = Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                     Text(
                        text = if (isMultiFile) "Текущий файл" else "%.1f%%".format(p.percent * 100f),
                        style = MaterialTheme.typography.bodySmall
                     )
                     Text(
                        text = if (isMultiFile) "%.1f%%".format(p.percent * 100f) else "%.1f MB/s".format(p.speedMBps),
                        style = MaterialTheme.typography.bodySmall
                     )
                     if (!isMultiFile && p.totalBytes > 0) {
                        Text(
                           text = "${fmtBytes(p.doneBytes)} / ${fmtBytes(p.totalBytes)}",
                           style = MaterialTheme.typography.bodySmall
                        )
                     }
                  }
                  if (isMultiFile && p.totalBytes > 0) {
                     Text(
                        text = "${fmtBytes(p.doneBytes)} / ${fmtBytes(p.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall
                     )
                  }
               }
            }
         }
      }
      
      // Лог конверсии (collapsible)
      if (vm.statusLog.isNotEmpty()) {
         var logVisible by remember { mutableStateOf(false) }
         
         TextButton(
            onClick = { logVisible = !logVisible },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = compactToggleButtonPadding
         ) {
            Icon(
               imageVector = if (logVisible) Icons.Filled.KeyboardArrowUp 
                           else Icons.Filled.KeyboardArrowDown,
               contentDescription = null,
               modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(if (logVisible) "Скрыть лог" else "Показать лог")
         }
         
         if (logVisible) {
            Surface(
               modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 80.dp, max = 200.dp),
               color = MaterialTheme.colorScheme.surfaceVariant,
               shape = MaterialTheme.shapes.medium
            ) {
               val logScrollState = rememberScrollState()
               LaunchedEffect(vm.statusLog.size) {
                  logScrollState.animateScrollTo(logScrollState.maxValue)
               }
               Column(
                  modifier = Modifier
                     .padding(10.dp)
                     .verticalScroll(logScrollState)
               ) {
                  for (entry in vm.statusLog) {
                     val color = when (entry.tag) {
                        "VERIFIED" -> Color(0xFF4CAF50)
                        "ERROR" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                     }
                     Text(
                        text = "[${entry.tag}]${entry.message}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = color
                     )
                  }
               }
            }
         }
      }
      
      // Статус сообщение
      val msg = vm.statusMessage
      if (msg != null) {
         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = if (vm.isSuccess)
                  MaterialTheme.colorScheme.primaryContainer
               else
                  MaterialTheme.colorScheme.errorContainer
            )
         ) {
            Text(
               text = msg,
               modifier = Modifier.padding(12.dp),
               style = MaterialTheme.typography.bodyMedium,
               color = if (vm.isSuccess)
                  MaterialTheme.colorScheme.onPrimaryContainer
               else
                  MaterialTheme.colorScheme.onErrorContainer
            )
         }
      }
   }
}

@Composable
fun FileQueueItem(
   file: FileEntry,
   onRemove: () -> Unit,
   enabled: Boolean
) {
   val isNsz = file.displayName.endsWith(".nsz", ignoreCase = true)
   
   Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
         containerColor = when (file.status) {
            FileStatus.Pending -> MaterialTheme.colorScheme.surfaceVariant
            FileStatus.Converting -> MaterialTheme.colorScheme.primaryContainer
            FileStatus.Completed -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            FileStatus.Failed -> MaterialTheme.colorScheme.errorContainer
         }
      )
   ) {
      Row(
         modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
         verticalAlignment = Alignment.Top
      ) {
         Icon(
            imageVector = if (isNsz) Icons.Filled.Description else Icons.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier
               .padding(top = 2.dp)
               .size(20.dp),
            tint = if (isNsz) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
         )
         Spacer(Modifier.width(8.dp))
         Column(modifier = Modifier.weight(1f)) {
            Text(
               text = file.displayName,
               style = MaterialTheme.typography.bodyMedium,
               fontWeight = if (isNsz) FontWeight.Bold else FontWeight.Normal
            )
            val statusText = when (file.status) {
               FileStatus.Pending -> "Ожидает"
               FileStatus.Converting -> "Конвертируется..."
               FileStatus.Completed -> "Завершено"
               FileStatus.Failed -> "Ошибка"
            }
            val sizeText = if (file.fileSize > 0) fmtBytes(file.fileSize) else null
            Text(
               text = if (sizeText != null) "$sizeText  |  $statusText" else statusText,
               style = MaterialTheme.typography.bodySmall,
               color = MaterialTheme.colorScheme.onSurfaceVariant
            )
         }
         
         if (enabled && file.status == FileStatus.Pending) {
            IconButton(onClick = onRemove) {
               Icon(Icons.Filled.Close, "Удалить")
            }
         }
      }
   }
}

/* ── Folder Mode UI ──────────────────────────────────────────────── */

@Composable
fun FolderModeUI(vm: MainViewModel, mode: ConversionMode.FolderMode, padding: PaddingValues) {
   val context = LocalContext.current
   
   val folderPicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocumentTree()
   ) { uri ->
      if (uri != null) {
         vm.selectFolder(context, uri)
      }
   }
   
   Column(
      modifier = Modifier
         .fillMaxSize()
         .padding(padding)
         .verticalScroll(rememberScrollState())
         .padding(all = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
   ) {
      // Кнопка выбора папки
      Button(
         onClick = { folderPicker.launch(null) },
         enabled = !vm.isConverting,
         modifier = Modifier.fillMaxWidth()
      ) {
         Icon(Icons.Filled.Folder, null)
         Spacer(Modifier.width(8.dp))
         Text("Выбрать папку")
      }
      
      // Информация о выбранной папке
      val structure = vm.folderStructure
      if (structure != null) {
         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
               Text(
                  text = "Папка выбрана",
                  style = MaterialTheme.typography.titleMedium
               )
               Text(
                  text = "NSZ файлов: ${structure.nszFiles.size}",
                  style = MaterialTheme.typography.bodyMedium
               )
               Text(
                  text = "Всего файлов: ${countAllFiles(structure.allFiles)}",
                  style = MaterialTheme.typography.bodyMedium
               )
               Text(
                  text = "Размер: ${fmtBytes(structure.totalSize)}",
                  style = MaterialTheme.typography.bodyMedium
               )
            }
         }
         
         // Предпросмотр структуры (collapsible)
         var showStructure by remember { mutableStateOf(false) }
         TextButton(
            onClick = { showStructure = !showStructure },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = compactToggleButtonPadding
         ) {
            Icon(
               if (showStructure) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
               null,
               modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(if (showStructure) "Скрыть структуру" else "Показать структуру")
         }
         
         if (showStructure) {
            Surface(
               modifier = Modifier.fillMaxWidth(),
               color = MaterialTheme.colorScheme.surfaceVariant,
               shape = MaterialTheme.shapes.medium
            ) {
               Column(modifier = Modifier.padding(12.dp)) {
                  FolderTreeView(structure.allFiles)
               }
            }
         }
         
         // Кнопка старта конверсии
         Button(
            onClick = { vm.startFolderConversion(context) },
            enabled = !vm.isConverting,
            modifier = Modifier.fillMaxWidth()
         ) {
            Text("Конвертировать папку")
         }
      }
      
      // Прогресс и логи
      if (vm.isConverting || vm.folderOverallProgress != null) {
         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier.padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
               Text(
                  text = "Обработка папки...",
                  style = MaterialTheme.typography.titleMedium
               )
               
               val overall = vm.folderOverallProgress
               if (overall != null) {
                  LinearProgressIndicator(
                     progress = { overall.percent },
                     modifier = Modifier.fillMaxWidth()
                  )
                  Row(
                     modifier = Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                     Text(
                        text = "Файлы: ${vm.folderProcessedFiles} / ${vm.folderTotalFiles}",
                        style = MaterialTheme.typography.bodySmall
                     )
                     Text(
                        text = "%.1f%%".format(overall.percent * 100f),
                        style = MaterialTheme.typography.bodySmall
                     )
                     Text(
                        text = "%.1f MB/s".format(overall.speedMBps),
                        style = MaterialTheme.typography.bodySmall
                     )
                  }
                  if (overall.totalBytes > 0) {
                     Text(
                        text = "${fmtBytes(overall.doneBytes)} / ${fmtBytes(overall.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall
                     )
                  }
               }

               val currentFileProgress = vm.folderCurrentFileProgress
               val currentFileName = vm.folderCurrentFileName
               if (currentFileName != null && currentFileProgress != null) {
                  Spacer(Modifier.height(4.dp))
                  Text(
                     text = currentFileName,
                     style = MaterialTheme.typography.bodyMedium,
                     maxLines = 1,
                     overflow = TextOverflow.Ellipsis
                  )
                  LinearProgressIndicator(
                     progress = { currentFileProgress.percent },
                     modifier = Modifier.fillMaxWidth()
                  )
                  Row(
                     modifier = Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                     Text(
                        text = "Текущий файл",
                        style = MaterialTheme.typography.bodySmall
                     )
                     Text(
                        text = "%.1f%%".format(currentFileProgress.percent * 100f),
                        style = MaterialTheme.typography.bodySmall
                     )
                  }
                  if (currentFileProgress.totalBytes > 0) {
                     Text(
                        text = "${fmtBytes(currentFileProgress.doneBytes)} / ${fmtBytes(currentFileProgress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall
                     )
                  }
               }
            }
         }
      }
      
      // Лог конверсии (collapsible)
      if (vm.statusLog.isNotEmpty()) {
         var logVisible by remember { mutableStateOf(false) }
         
         TextButton(
            onClick = { logVisible = !logVisible },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = compactToggleButtonPadding
         ) {
            Icon(
               imageVector = if (logVisible) Icons.Filled.KeyboardArrowUp 
                           else Icons.Filled.KeyboardArrowDown,
               contentDescription = null,
               modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(if (logVisible) "Скрыть лог" else "Показать лог")
         }
         
         if (logVisible) {
            Surface(
               modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 80.dp, max = 200.dp),
               color = MaterialTheme.colorScheme.surfaceVariant,
               shape = MaterialTheme.shapes.medium
            ) {
               val logScrollState = rememberScrollState()
               LaunchedEffect(vm.statusLog.size) {
                  logScrollState.animateScrollTo(logScrollState.maxValue)
               }
               Column(
                  modifier = Modifier
                     .padding(10.dp)
                     .verticalScroll(logScrollState)
               ) {
                  for (entry in vm.statusLog) {
                     val color = when (entry.tag) {
                        "VERIFIED" -> Color(0xFF4CAF50)
                        "ERROR" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                     }
                     Text(
                        text = "[${entry.tag}]${entry.message}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = color
                     )
                  }
               }
            }
         }
      }
      
      // Статус сообщение
      val msg = vm.statusMessage
      if (msg != null) {
         Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
               containerColor = if (vm.isSuccess)
                  MaterialTheme.colorScheme.primaryContainer
               else
                  MaterialTheme.colorScheme.errorContainer
            )
         ) {
            Text(
               text = msg,
               modifier = Modifier.padding(12.dp),
               style = MaterialTheme.typography.bodyMedium,
               color = if (vm.isSuccess)
                  MaterialTheme.colorScheme.onPrimaryContainer
               else
                  MaterialTheme.colorScheme.onErrorContainer
            )
         }
      }
   }
}

@Composable
fun FolderTreeView(nodes: List<FileNode>, depth: Int = 0) {
   val startPadding: Dp = (depth * 16).dp
   Column {
      nodes.forEach { node ->
         when (node) {
            is FileNode.File -> {
               Row(
                  modifier = Modifier
                     .fillMaxWidth()
                     .padding(start = startPadding, top = 2.dp, bottom = 2.dp),
                  verticalAlignment = Alignment.CenterVertically
               ) {
                  Icon(
                     if (node.isNsz) Icons.Filled.Description else Icons.Filled.InsertDriveFile,
                     null,
                     modifier = Modifier.size(16.dp),
                     tint = if (node.isNsz) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Spacer(Modifier.width(4.dp))
                  Text(
                     text = node.name,
                     style = MaterialTheme.typography.bodySmall,
                     fontWeight = if (node.isNsz) FontWeight.Bold else FontWeight.Normal
                  )
               }
            }
            is FileNode.Directory -> {
               Row(
                  modifier = Modifier
                     .fillMaxWidth()
                     .padding(start = startPadding, top = 2.dp, bottom = 2.dp),
                  verticalAlignment = Alignment.CenterVertically
               ) {
                  Icon(
                     Icons.Filled.Folder,
                     null,
                     modifier = Modifier.size(16.dp),
                     tint = MaterialTheme.colorScheme.primary
                  )
                  Spacer(Modifier.width(4.dp))
                  Text(
                     text = node.name + "/",
                     style = MaterialTheme.typography.bodySmall,
                     fontWeight = FontWeight.Bold
                  )
               }
               FolderTreeView(node.children, depth + 1)
            }
         }
      }
   }
}

private fun countAllFiles(nodes: List<FileNode>): Int {
   var count = 0
   for (node in nodes) {
      when (node) {
         is FileNode.File -> count++
         is FileNode.Directory -> count += countAllFiles(node.children)
      }
   }
   return count
}

/* ── Activity ────────────────────────────────────────────────────── */

class MainActivity : ComponentActivity() {
   private val vm: MainViewModel by lazy {
      androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
   }
   
   override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      lifecycleScope.launch(Dispatchers.IO) {
         TempFileManager.cleanupManagedCache(this@MainActivity)
      }
      enableEdgeToEdge()
      setContent {
         AndroNSZTheme {
            AndroNSZApp(vm)
         }
      }
   }
}

/* ── Root composable ─────────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroNSZApp(vm: MainViewModel) {
   val context = LocalContext.current
   
   LaunchedEffect(Unit) {
      vm.checkKeys(context)
   }
   
   val keysPicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument()
   ) { uri ->
      if (uri != null) vm.installKeys(context, uri)
   }
   
   // Simple navigation via state
   when (vm.currentScreen) {
      Screen.ModeSelection -> {
         ModeSelectionScreen(
            onModeSelected = { mode ->
               vm.selectMode(mode)
               vm.currentScreen = Screen.Conversion
            },
            keysInstalled = vm.keysInstalled,
            onInstallKeys = { keysPicker.launch(arrayOf("*/*")) }
         )
      }
      Screen.Conversion -> {
         ConversionScreen(
            vm = vm,
            onBackToModeSelection = {
               vm.currentScreen = Screen.ModeSelection
               vm.resetConversionState()
            },
            onInstallKeys = { keysPicker.launch(arrayOf("*/*")) }
         )
      }
   }
}

/* ── Conversion Screen ───────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionScreen(
   vm: MainViewModel,
   onBackToModeSelection: () -> Unit,
   onInstallKeys: () -> Unit
) {
   val context = LocalContext.current
   var settingsMenuExpanded by remember { mutableStateOf(false) }
   
   Scaffold(
      topBar = {
         CenterAlignedTopAppBar(
            title = { Text("AndroNSZ") },
            navigationIcon = {
               IconButton(
                  onClick = onBackToModeSelection,
                  enabled = !vm.isConverting
               ) {
                  Icon(
                     imageVector = Icons.Filled.ArrowBack,
                     contentDescription = "Back",
                     tint = MaterialTheme.colorScheme.onPrimary,
                  )
               }
            },
            actions = {
               Box {
                  IconButton(onClick = { settingsMenuExpanded = true }) {
                     Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onPrimary,
                     )
                  }
                  DropdownMenu(
                     expanded = settingsMenuExpanded,
                     onDismissRequest = { settingsMenuExpanded = false }
                  ) {
                     DropdownMenuItem(
                        text = { Text("Изменить выбранные prod.keys") },
                        onClick = {
                           settingsMenuExpanded = false
                           onInstallKeys()
                        }
                     )
                     DropdownMenuItem(
                        text = { Text("Удалить выбранные prod.keys") },
                        onClick = {
                           settingsMenuExpanded = false
                           KeysManager.deleteKeys(context)
                           vm.checkKeys(context)
                        }
                     )
                  }
               }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
               containerColor = MaterialTheme.colorScheme.primary,
               titleContentColor = MaterialTheme.colorScheme.onPrimary,
            )
         )
      }
   ) { padding ->
      // Display content based on conversion mode
      when (val mode = vm.conversionMode) {
         ConversionMode.None -> {
            // Fallback to single file mode (legacy)
            LegacySingleFileUI(vm, padding, onInstallKeys)
         }
         is ConversionMode.SingleFiles -> {
            SingleFilesUI(vm, padding)
         }
         is ConversionMode.FolderMode -> {
            FolderModeUI(vm, mode, padding)
         }
      }
   }
}

/* ── Legacy Single File UI ───────────────────────────────────────── */

@Composable
fun LegacySingleFileUI(vm: MainViewModel, padding: PaddingValues, onInstallKeys: () -> Unit) {
   val context = LocalContext.current
   
   val filePicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument()
   ) { uri ->
      if (uri != null) {
         val name = resolveDisplayName(context, uri)
         vm.pickFile(uri, name)
      }
   }
   
   Column(
      modifier = Modifier
         .fillMaxSize()
         .padding(padding)
         .verticalScroll(rememberScrollState())
         .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(20.dp),
   ) {
      
      /* ── prod.keys warning (only when not installed) ── */
      if (!vm.keysInstalled) {
            Card(
               modifier = Modifier.fillMaxWidth(),
               onClick = onInstallKeys,
               colors = CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.errorContainer
               )
            ) {
               Column(
                  modifier = Modifier
                     .fillMaxWidth()
                     .padding(16.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(4.dp),
               ) {
                  Text(
                     text = "prod.keys required for proper NSZ to NSP decompression.",
                     style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onErrorContainer,
                     textAlign = TextAlign.Center,
                  )
                  Text(
                     text = "Install prod.keys",
                     style = MaterialTheme.typography.labelLarge,
                     color = MaterialTheme.colorScheme.onErrorContainer,
                     textAlign = TextAlign.Center,
                  )
               }
            }
         }
         
         /* ── File picker card ── */
         Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (!vm.isConverting) filePicker.launch(arrayOf("*/*")) },
            enabled = vm.keysInstalled && !vm.isConverting,
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
         ) {
            Column(
               modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
               horizontalAlignment = Alignment.CenterHorizontally,
            ) {
               Text(
                  text = if (vm.selectedName != null) "Selected file" else "Tap to select NSZ file",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
               )
               if (vm.selectedName != null) {
                  Spacer(Modifier.height(4.dp))
                  Text(
                     text = vm.selectedName!!,
                     style = MaterialTheme.typography.bodyLarge,
                     maxLines = 2,
                     overflow = TextOverflow.Ellipsis,
                     textAlign = TextAlign.Center,
                  )
               }
            }
         }
         
         /* ── Output destination note ── */
         Text(
            text = "Output: Downloads folder",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
         )
         
         /* ── Convert / Cancel button ── */
         if (vm.isConverting) {
            Button(
               onClick = { vm.cancel() },
               modifier = Modifier.fillMaxWidth(),
               colors = ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.error
               )
            ) {
               Text("Cancel")
            }
         } else {
            Button(
               onClick  = { vm.startConversion(context) },
               enabled  = vm.selectedUri != null,
               modifier = Modifier.fillMaxWidth(),
            ) {
               Text("Convert to NSP")
            }
         }
         
         /* ── Progress section ── */
         if (vm.isConverting || vm.progress != null) {
            val p = vm.progress
            if (p != null) {
               Column(
                  modifier = Modifier.fillMaxWidth(),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
               ) {
                  LinearProgressIndicator(
                     progress = { p.percent },
                     modifier = Modifier.fillMaxWidth(),
                  )
                  Row(
                     modifier = Modifier.fillMaxWidth(),
                     horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                     Text(
                        text = "%.1f%%".format(p.percent * 100f),
                        style = MaterialTheme.typography.bodySmall,
                     )
                     if (p.totalBytes > 0) {
                        Text(
                           text = "${fmtBytes(p.doneBytes)} / ${fmtBytes(p.totalBytes)}",
                           style = MaterialTheme.typography.bodySmall,
                        )
                     }
                     Text(
                        text = "%.1f MB/s".format(p.speedMBps),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                     )
                  }
               }
            } else {
               LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
         }
         
         /* ── Conversion log (collapsible) ── */
         if (vm.statusLog.isNotEmpty()) {
            var logVisible by remember { mutableStateOf(false) }
            
            TextButton(
               onClick = { logVisible = !logVisible },
               modifier = Modifier.fillMaxWidth(),
            ) {
               Icon(
                  imageVector = if (logVisible) Icons.Filled.KeyboardArrowUp
                                else Icons.Filled.KeyboardArrowDown,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
               )
               Spacer(Modifier.width(4.dp))
               Text(if (logVisible) "Hide log" else "Show log")
            }
            
            if (logVisible) {
               val logScrollState = rememberScrollState()
               LaunchedEffect(vm.statusLog.size) {
                  logScrollState.animateScrollTo(logScrollState.maxValue)
               }
               Surface(
                  modifier = Modifier
                     .fillMaxWidth()
                     .heightIn(min = 80.dp, max = 260.dp),
                  color  = MaterialTheme.colorScheme.surfaceVariant,
                  shape  = MaterialTheme.shapes.medium,
               ) {
                  Column(
                     modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .verticalScroll(logScrollState),
                  ) {
                     for (entry in vm.statusLog) {
                        val color = when (entry.tag) {
                           "VERIFIED" -> Color(0xFF4CAF50)
                           "NCA_HASH" -> MaterialTheme.colorScheme.onSurfaceVariant
                           "ERROR"    -> MaterialTheme.colorScheme.error
                           else       -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                           text       = "[${entry.tag}]${entry.message}",
                           style      = MaterialTheme.typography.bodySmall,
                           fontFamily = FontFamily.Monospace,
                           color      = color,
                        )
                     }
                  }
               }
            }
         }
         
         /* ── Status message ── */
         val msg = vm.statusMessage
         if (msg != null) {
            Card(
               modifier = Modifier.fillMaxWidth(),
               colors = CardDefaults.cardColors(
                  containerColor = if (vm.isSuccess)
                     MaterialTheme.colorScheme.primaryContainer
                  else
                     MaterialTheme.colorScheme.errorContainer
               )
            ) {
               Text(
                  text = msg,
                  modifier = Modifier.padding(12.dp),
                  style = MaterialTheme.typography.bodyMedium,
                  color = if (vm.isSuccess)
                     MaterialTheme.colorScheme.onPrimaryContainer
                  else
                     MaterialTheme.colorScheme.onErrorContainer,
               )
            }
         }
   }
}

/* ── Helpers ─────────────────────────────────────────────────────── */

private fun resolveDisplayName(context: android.content.Context, uri: Uri): String {
   context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
         val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
         if (idx >= 0) return cursor.getString(idx)
      }
   }
   return uri.lastPathSegment ?: "unknown.nsz"
}

private fun getUriSize(context: android.content.Context, uri: Uri): Long {
   context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
         val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
         if (idx >= 0 && !cursor.isNull(idx)) {
            return cursor.getLong(idx)
         }
      }
   }
   return 0L
}

private fun fmtBytes(bytes: Long): String {
   return when {
      bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
      bytes >= 1_048_576L     -> "%.0f MB".format(bytes / 1_048_576.0)
      else                    -> "%.0f KB".format(bytes / 1024.0)
   }
}

/* ── New UI Composables ──────────────────────────────────────────── */

@Composable
fun ModeSelectionScreen(
   onModeSelected: (ConversionMode) -> Unit,
   keysInstalled: Boolean,
   onInstallKeys: () -> Unit
) {
   Column(
      modifier = Modifier
         .fillMaxSize()
         .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp)
   ) {
      // Предупреждение о prod.keys (если не установлены)
      if (!keysInstalled) {
         Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onInstallKeys,
            colors = CardDefaults.cardColors(
               containerColor = MaterialTheme.colorScheme.errorContainer
            )
         ) {
            Column(
               modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
               horizontalAlignment = Alignment.CenterHorizontally,
               verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
               Text(
                  text = "prod.keys required for proper NSZ to NSP decompression.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  textAlign = TextAlign.Center,
               )
               Text(
                  text = "Install prod.keys",
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  textAlign = TextAlign.Center,
               )
            }
         }
      }
      
      Spacer(modifier = Modifier.height(32.dp))
      
      Text(
         text = "Выберите режим работы",
         style = MaterialTheme.typography.headlineMedium,
         textAlign = TextAlign.Center,
      )
      
      Spacer(modifier = Modifier.height(16.dp))
      
      // Карточка режима 1: Выбрать файлы
      ModeCard(
         title = "Выбрать файлы",
         description = "Выберите один или несколько NSZ файлов для конверсии",
         icon = Icons.Filled.InsertDriveFile,
         enabled = keysInstalled,
         onClick = { onModeSelected(ConversionMode.SingleFiles(emptyList())) }
      )
      
      // Карточка режима 2: Выбрать папку
      ModeCard(
         title = "Выбрать папку",
         description = "Конвертирует все NSZ файлы в папке, сохраняя структуру",
         icon = Icons.Filled.Folder,
         enabled = keysInstalled,
         onClick = { onModeSelected(ConversionMode.FolderMode(Uri.EMPTY, null)) }
      )
   }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeCard(
   title: String,
   description: String,
   icon: ImageVector,
   enabled: Boolean,
   onClick: () -> Unit
) {
   Card(
      modifier = Modifier
         .fillMaxWidth()
         .height(120.dp),
      onClick = onClick,
      enabled = enabled,
      colors = CardDefaults.cardColors(
         containerColor = MaterialTheme.colorScheme.primaryContainer
      )
   ) {
      Row(
         modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
         horizontalArrangement = Arrangement.spacedBy(16.dp),
         verticalAlignment = Alignment.CenterVertically
      ) {
         Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
         )
         Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
         ) {
            Text(
               text = title,
               style = MaterialTheme.typography.titleLarge,
               color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
               text = description,
               style = MaterialTheme.typography.bodyMedium,
               color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
         }
      }
   }
}
