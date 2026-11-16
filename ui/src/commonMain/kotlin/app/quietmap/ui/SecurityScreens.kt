package app.quietmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.quietmap.core.Recovery

/**
 * Shown instead of everything else when saved data exists but can't be read. Nothing is shared
 * and nothing is overwritten until one of these choices succeeds.
 */
@Composable
fun RecoveryScreen(recovery: Recovery, actions: Actions, onRestore: () -> Unit) {
    var confirmFresh by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Quietmap can't open your saved data", style = MaterialTheme.typography.headlineSmall)
        Text(recovery.reason + ".", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Your data is still on this phone and hasn't been changed. Location sharing is off until this is fixed.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (recovery.retryable) {
            PrimaryButton(if (working) "Trying…" else "Try again", {
                working = true
                actions.retryLoad { working = false }
            }, Modifier.fillMaxWidth(), enabled = !working)
        }
        TonalButton("Restore from a backup", onRestore, Modifier.fillMaxWidth())
        TextButton(onClick = { confirmFresh = true }, modifier = Modifier.fillMaxWidth()) { Text("Start over") }
        Caption(
            if (recovery.retryable) "If the phone was just restarted, unlock it and try again."
            else "If you have a backup file, restoring it brings back your circles.",
        )
    }
    if (confirmFresh) {
        ConfirmSheet(
            title = "Start over?",
            text = "Quietmap gets a new device code and forgets your circles. Your old data stays on this phone, unused. To rejoin a circle you'll need a new invite.",
            confirm = "Start over",
            destructive = true,
            onConfirm = { confirmFresh = false; actions.startFresh() },
            onDismiss = { confirmFresh = false },
        )
    }
}
