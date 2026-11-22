package app.opaq.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opaq.core.model.AppState

/**
 * Settings → Diagnostic logs (#154): what's kept, the latest events, and buttons to send the log
 * (through the share sheet, to whoever the person picks) or delete it.
 */
@Composable
fun DiagnosticsScreen(state: AppState, actions: Actions, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, state.keepDiagnostics) { text = runCatching { actions.diagnosticsText() }.getOrDefault("") }
    val recent = remember(text) { text.lines().drop(3).filter { it.isNotBlank() }.takeLast(40).reversed() }

    ScreenScaffold("Diagnostic logs", onBack) {
        Caption(
            "If sharing stops, this log can show why: the app or its service ending, permissions, battery saving, relays or pauses. " +
                "It keeps events and times for 7 days. It never holds locations, keys, names or messages, and stays on this phone unless you send it.",
            Gutter.padding(bottom = 8.dp),
        )
        SwitchRow("Keep diagnostic logs", if (state.keepDiagnostics) "On" else "Off. Turning it off deletes the log.", state.keepDiagnostics, AppIcons.Info) {
            actions.setKeepDiagnostics(it)
            refresh++
        }
        SettingsRow("Export logs", "Read it, then choose who to send it to", AppIcons.Share, onClick = { actions.exportDiagnostics() })
        // The website's report form has a box for the log: copy it from the share sheet and paste it there.
        val platform = LocalPlatform.current
        SettingsRow("Report a problem", "Opens the report form; paste the log in it", AppIcons.Flag, onClick = { platform.openUrl(REPORT_URL) })
        SettingsRow("Clear logs", icon = AppIcons.Delete, titleColor = MaterialTheme.colorScheme.error, onClick = { confirmClear = true })
        SectionHeader("Latest events")
        if (recent.isEmpty() || (recent.size == 1 && recent[0].startsWith("("))) {
            Caption("Nothing recorded yet.", Gutter)
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Gutter.fillMaxWidth().padding(bottom = 24.dp),
            ) {
                Column(Modifier.padding(14.dp).horizontalScroll(rememberScrollState())) {
                    recent.forEach { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, softWrap = false)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmSheet(
            title = "Clear the diagnostic log?",
            text = "Everything recorded so far is deleted from this phone.",
            confirm = "Clear logs",
            destructive = true,
            onConfirm = { confirmClear = false; actions.clearDiagnostics { refresh++ } },
            onDismiss = { confirmClear = false },
        )
    }
}

