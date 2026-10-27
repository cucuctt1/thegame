package dev.tilesolver

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.tilesolver.capture.CaptureMode
import dev.tilesolver.capture.CaptureService
import dev.tilesolver.model.SessionState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph
    private var pendingPackage: String = ""
    private var pendingCaptureResultCode = Activity.RESULT_CANCELED
    private var pendingCaptureData: Intent? = null
    private var captureReady by mutableStateOf(false)

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK || pendingPackage.isBlank()) return@registerForActivityResult
        pendingCaptureResultCode = result.resultCode
        pendingCaptureData = data
        captureReady = true
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as SolverApplication).graph
        if (android.os.Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { SolverScreen() }
    }

    @Composable private fun SolverScreen() {
        val scope = rememberCoroutineScope()
        val savedPackage by graph.settings.gamePackage.collectAsStateWithLifecycle(initialValue = "")
        val session by graph.sessionController.state.collectAsStateWithLifecycle()
        val captureMode by CaptureService.activeMode.collectAsStateWithLifecycle()
        var gamePackage by remember(savedPackage) { mutableStateOf(savedPackage) }
        var accessibilityEnabled by remember { mutableStateOf(isAccessibilityEnabled()) }
        LifecycleResumeEffect(Unit) {
            accessibilityEnabled = isAccessibilityEnabled()
            onPauseOrDispose { }
        }
        val sessionActive = session.isActive()
        val captureActive = captureMode != null
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF9DB2FF))) {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Tile Solver", style = MaterialTheme.typography.headlineMedium)
                    Text("Private, offline solver for the calibrated triple-tile game.")
                    StatusCard("1. Accessibility gestures", if (accessibilityEnabled) "Enabled" else "Required") {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    StatusCard("2. Emergency overlay", if (Settings.canDrawOverlays(this@MainActivity)) "Enabled" else "Recommended") {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }
                    OutlinedTextField(
                        value = gamePackage,
                        onValueChange = { gamePackage = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !captureReady && !captureActive && !sessionActive,
                        singleLine = true,
                        label = { Text("Game package name") },
                        supportingText = { Text("Example: com.publisher.game — only this foreground app may be tapped") },
                    )
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Session", style = MaterialTheme.typography.titleMedium)
                            Text(when {
                                captureReady -> "Ready: choose Start, Test, or Calibrate"
                                captureMode == CaptureMode.PREVIEW -> "Previewing next move — no taps"
                                captureMode == CaptureMode.CALIBRATE -> "Adjusting detection area — no taps"
                                else -> session.describe()
                            })
                        }
                    }
                    Button(
                        onClick = {
                            val normalized = gamePackage.trim()
                            if (normalized.isNotEmpty() && accessibilityEnabled) {
                                pendingPackage = normalized
                                scope.launch { graph.settings.setGamePackage(normalized) }
                                val manager = getSystemService(MediaProjectionManager::class.java)
                                captureLauncher.launch(manager.createScreenCaptureIntent())
                            }
                        },
                        enabled = gamePackage.isNotBlank() && accessibilityEnabled && !captureReady && !captureActive && !sessionActive,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(if (captureReady) "Capture approved" else "Approve capture") }
                    Button(
                        onClick = { startApprovedCapture(CaptureMode.SOLVE) },
                        enabled = captureReady && accessibilityEnabled,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Start solver") }
                    OutlinedButton(
                        onClick = { startApprovedCapture(CaptureMode.PREVIEW) },
                        enabled = captureReady && Settings.canDrawOverlays(this@MainActivity),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Test next move overlay") }
                    OutlinedButton(
                        onClick = { startApprovedCapture(CaptureMode.CALIBRATE) },
                        enabled = captureReady && Settings.canDrawOverlays(this@MainActivity),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Calibrate detection area") }
                    Text(
                        "Drag green corners around the board and orange corners around the 7-slot tray. Other screen touches pass through; valid non-overlapping areas save automatically.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            if (captureReady) clearPendingCapture()
                            else startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                        },
                        enabled = captureReady || captureActive || sessionActive,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop solver") }
                    Text("Safety: board-only taps, no ads, purchases, boosters, retries, or network access.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable private fun StatusCard(title: String, status: String, action: () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title); Text(status, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = action) { Text("Open settings") }
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun startApprovedCapture(mode: CaptureMode) {
        val data = pendingCaptureData ?: return
        val targetPackage = pendingPackage
        val intent = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, pendingCaptureResultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            .putExtra(CaptureService.EXTRA_GAME_PACKAGE, targetPackage)
            .putExtra(CaptureService.EXTRA_MODE, mode.name)
        clearPendingCapture()
        ContextCompat.startForegroundService(this, intent)
        packageManager.getLaunchIntentForPackage(targetPackage)?.let(::startActivity)
    }

    private fun clearPendingCapture() {
        pendingCaptureResultCode = Activity.RESULT_CANCELED
        pendingCaptureData = null
        captureReady = false
    }
}

private fun SessionState.isActive(): Boolean = when (this) {
    SessionState.Calibrating, SessionState.Observing, SessionState.Planning,
    is SessionState.Tapping, is SessionState.Verifying -> true
    else -> false
}

private fun SessionState.describe(): String = when (this) {
    SessionState.Idle -> "Idle"
    SessionState.Calibrating -> "Calibrating"
    SessionState.Observing -> "Observing board"
    SessionState.Planning -> "Planning move"
    is SessionState.Tapping -> "Tapping: ${move.rationale}"
    is SessionState.Verifying -> "Verifying move"
    is SessionState.Paused -> "Paused: $reason"
    is SessionState.Stopped -> "Stopped: ${detail.ifBlank { reason.name }}"
    SessionState.Won -> "Won"
    SessionState.Lost -> "Lost"
    is SessionState.Fault -> "Fault: $message"
}
