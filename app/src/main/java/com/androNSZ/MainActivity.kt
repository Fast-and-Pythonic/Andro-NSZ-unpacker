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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androNSZ.ui.theme.AndroNSZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class LogEntry(val tag: String, val message: String)

/* ── ViewModel ───────────────────────────────────────────────────── */

class MainViewModel : ViewModel() {
   
   var selectedUri   by mutableStateOf<Uri?>(null)
   var selectedName  by mutableStateOf<String?>(null)
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
}

/* ── Activity ────────────────────────────────────────────────────── */

class MainActivity : ComponentActivity() {
   private val vm: MainViewModel by lazy {
      androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
   }
   
   override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
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
   
   val filePicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument()
   ) { uri ->
      if (uri != null) {
         val name = resolveDisplayName(context, uri)
         vm.pickFile(uri, name)
      }
   }
   
   val keysPicker = rememberLauncherForActivityResult(
      ActivityResultContracts.OpenDocument()
   ) { uri ->
      if (uri != null) vm.installKeys(context, uri)
   }
   
   Scaffold(
      topBar = {
         TopAppBar(
            title = { Text("AndroNSZ") },
            colors = TopAppBarDefaults.topAppBarColors(
               containerColor = MaterialTheme.colorScheme.primary,
               titleContentColor = MaterialTheme.colorScheme.onPrimary,
            )
         )
      }
   ) { padding ->
      Column(
         modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp, vertical = 16.dp),
         horizontalAlignment = Alignment.CenterHorizontally,
         verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
         
         Spacer(Modifier.height(8.dp))
         
         /* ── prod.keys warning (only when not installed) ── */
         if (!vm.keysInstalled) {
            Card(
               modifier = Modifier.fillMaxWidth(),
               onClick = { keysPicker.launch(arrayOf("*/*")) },
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
         
         /* ── Conversion log ── */
         if (vm.statusLog.isNotEmpty()) {
            Surface(
               modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 80.dp, max = 260.dp),
               color  = MaterialTheme.colorScheme.surfaceVariant,
               shape  = MaterialTheme.shapes.medium,
            ) {
               LazyColumn(
                  modifier      = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                  reverseLayout = true,
               ) {
                  items(vm.statusLog.asReversed()) { entry ->
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

private fun fmtBytes(bytes: Long): String {
   return when {
      bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
      bytes >= 1_048_576L     -> "%.0f MB".format(bytes / 1_048_576.0)
      else                    -> "%.0f KB".format(bytes / 1024.0)
   }
}