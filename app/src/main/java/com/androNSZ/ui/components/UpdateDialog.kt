package com.androNSZ.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androNSZ.R
import com.androNSZ.model.UpdateState

/**
 * Dialog driven by [UpdateState]: shows an available update (with changelog and
 * Update / Later / Skip actions), download progress, an "up to date" result, or an
 * error. The caller gates visibility on `showUpdateDialog`.
 */
@Composable
fun UpdateDialog(
   state: UpdateState,
   onUpdate: () -> Unit,
   onHide: () -> Unit,
   onRetry: () -> Unit,
   onDismiss: () -> Unit
) {
   // The APK download must not be interrupted by an outside tap.
   val dismissable = state !is UpdateState.Downloading

   AlertDialog(
      onDismissRequest = { if (dismissable) onDismiss() },
      title = { Text(stringResource(R.string.update_dialog_title)) },
      text = {
         when (state) {
            is UpdateState.Checking -> {
               Row(
                  verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp)
               ) {
                  CircularProgressIndicator(modifier = Modifier.height(24.dp))
                  Text(text = stringResource(R.string.msg_checking_update))
               }
            }
            is UpdateState.Available -> {
               Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                  Text(stringResource(R.string.msg_update_available, state.release.versionName))
                  if (state.release.notes.isNotEmpty()) {
                     Spacer(modifier = Modifier.height(12.dp))
                     Text(
                        text = stringResource(R.string.update_notes_label),
                        style = MaterialTheme.typography.titleSmall
                     )
                     Spacer(modifier = Modifier.height(4.dp))
                     Text(
                        text = state.release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                     )
                  }
               }
            }
            is UpdateState.Downloading -> {
               Column {
                  Text(stringResource(R.string.msg_downloading_update))
                  Spacer(modifier = Modifier.height(12.dp))
                  if (state.progress >= 0f) {
                     LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                     )
                  } else {
                     LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                  }
               }
            }
            is UpdateState.UpToDate -> Text(stringResource(R.string.msg_up_to_date))
            is UpdateState.Failed ->
               Text(stringResource(R.string.msg_update_check_failed, state.reason))
            is UpdateState.Idle -> {}
         }
      },
      confirmButton = {
         when (state) {
            is UpdateState.Available ->
               TextButton(onClick = onUpdate) {
                  Text(stringResource(R.string.action_update_now))
               }
            is UpdateState.Failed ->
               TextButton(onClick = onRetry) {
                  Text(stringResource(R.string.action_check_updates))
               }
            is UpdateState.UpToDate ->
               TextButton(onClick = onDismiss) {
                  Text(stringResource(R.string.action_done))
               }
            else -> {}
         }
      },
      dismissButton = {
         when (state) {
            is UpdateState.Available ->
               Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  TextButton(onClick = onHide) {
                     Text(stringResource(R.string.action_hide))
                  }
                  TextButton(onClick = onDismiss) {
                     Text(stringResource(R.string.action_update_later))
                  }
               }
            is UpdateState.Failed ->
               TextButton(onClick = onDismiss) {
                  Text(stringResource(R.string.action_cancel))
               }
            else -> {}
         }
      }
   )
}
