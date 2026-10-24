package com.mobilestreaming.studio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobilestreaming.studio.StudioViewModel
import com.mobilestreaming.studio.core.model.Scene
import com.mobilestreaming.studio.core.model.StreamPhase
import com.mobilestreaming.studio.core.model.VisualSource
import com.mobilestreaming.studio.streaming.compositor.ScenePreviewView
import kotlin.math.abs

@Composable
fun StudioScreen(
    viewModel: StudioViewModel,
    onNavigate: (StudioDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stream by viewModel.streamState.collectAsState()
    val recording by viewModel.recordingState.collectAsState()
    val level by viewModel.audioLevel.collectAsState()
    val settingsConfig by viewModel.streamConfig.collectAsState()
    val activeConfig by viewModel.activeEncoderConfig.collectAsState()
    val library by viewModel.sceneLibrary.collectAsState()
    val cameraError by viewModel.cameraError.collectAsState()
    val config = activeConfig ?: settingsConfig
    var confirmStop by remember { mutableStateOf(false) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Live monitor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${library.activeScene.name}  •  ${config.width}×${config.height} @ ${config.frameRate} FPS",
                    color = StudioMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = viewModel::switchCamera) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera")
            }
        }
        SceneMonitor(viewModel, library.activeScene, library.selectedSourceId)
        cameraError?.let { ErrorCard(it) }
        stream.errorMessage?.let { ErrorCard(it) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Status", stream.phase.name, Modifier.weight(1f))
            MetricCard("Duration", formatDuration(stream.statistics.durationMs), Modifier.weight(1f))
            MetricCard("Bitrate", formatBitrate(stream.statistics.bitrateBps), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Dropped", stream.statistics.droppedFrames.toString(), Modifier.weight(1f))
            MetricCard("Network", if (stream.statistics.networkAvailable) "Online" else "Offline", Modifier.weight(1f))
            MetricCard("Thermal", thermalLabel(stream.statistics.thermalStatus), Modifier.weight(1f))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AudioMeter(level.peak, level.rms, level.clipped)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { if (stream.isSessionActive) confirmStop = true else viewModel.startStream() },
                        enabled = stream.phase != StreamPhase.STOPPING,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (stream.phase == StreamPhase.CONNECTING || stream.phase == StreamPhase.RECONNECTING) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(if (stream.isSessionActive) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (stream.isSessionActive) "Stop Stream" else "Start Stream")
                    }
                    OutlinedButton(
                        onClick = { if (recording.isActive) viewModel.stopRecording() else viewModel.startRecording() },
                        enabled = !recording.isFinalizing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.FiberManualRecord, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                recording.isFinalizing -> "Finalizing"
                                recording.isActive -> "Stop Recording"
                                else -> "Start Recording"
                            },
                        )
                    }
                }
                if (recording.isActive || recording.isFinalizing) {
                    Text(
                        "${recording.displayName.orEmpty()}  •  ${formatDuration(recording.durationMs)}  •  ${formatBytes(recording.sizeBytes)}",
                        color = StudioMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        CameraControls(viewModel)

        Text("Quick access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioDestination.entries.filter { it != StudioDestination.Studio && it != StudioDestination.Settings }.forEach { destination ->
                AssistChip(onClick = { onNavigate(destination) }, label = { Text(destination.label) })
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop the live stream?") },
            text = { Text("Viewers will be disconnected. Local recording continues if it is active.") },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; viewModel.stopStream() }) { Text("Stop Stream") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SceneMonitor(viewModel: StudioViewModel, scene: Scene, selectedId: String?) {
    val config by viewModel.streamConfig.collectAsState()
    val activeConfig by viewModel.activeEncoderConfig.collectAsState()
    val active = activeConfig ?: config
    val selected = scene.sources.firstOrNull { it.id == selectedId }
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(active.width.toFloat() / active.height.toFloat())
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { context -> ScenePreviewView(context).apply { bind(viewModel.previewCompositor) } },
            update = { view -> view.bind(viewModel.previewCompositor) },
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            if (selected != null && !selected.locked) {
                val left = (selected.transform.centerX - selected.transform.width / 2f) * size.width
                val top = (selected.transform.centerY - selected.transform.height / 2f) * size.height
                val width = selected.transform.width * size.width
                val height = selected.transform.height * size.height
                if (abs(selected.transform.centerX - 0.5f) < 0.03f) {
                    drawLine(StudioTeal, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f)
                }
                if (abs(selected.transform.centerY - 0.5f) < 0.03f) {
                    drawLine(StudioTeal, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
                }
                drawRect(StudioTeal, Offset(left, top), Size(width, height), style = Stroke(width = 3f))
                listOf(Offset(left, top), Offset(left + width, top), Offset(left, top + height), Offset(left + width, top + height))
                    .forEach { drawCircle(StudioTeal, radius = 8f, center = it) }
            }
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(scene.id, selectedId) {
                    detectTapGestures(
                        onDoubleTap = { offset -> viewModel.focusAt(offset.x / size.width, offset.y / size.height) },
                        onTap = { offset ->
                            val x = offset.x / size.width
                            val y = offset.y / size.height
                            val hit = scene.sources.asReversed().firstOrNull { source ->
                                source.visible && x >= source.transform.centerX - source.transform.width / 2f &&
                                    x <= source.transform.centerX + source.transform.width / 2f &&
                                    y >= source.transform.centerY - source.transform.height / 2f &&
                                    y <= source.transform.centerY + source.transform.height / 2f
                            }
                            viewModel.selectSource(hit?.id)
                        },
                    )
                }
                .pointerInput(scene.id, selectedId) {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        if (selectedId != null) {
                            viewModel.transformSelectedSource(
                                pan.x / size.width,
                                pan.y / size.height,
                                zoom,
                                rotation,
                            )
                        }
                    }
                    viewModel.finishSourceGesture()
                },
        )
    }
}

@Composable
private fun CameraControls(viewModel: StudioViewModel) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var torch by remember { mutableStateOf(false) }
    val settings by viewModel.appSettings.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Camera controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Double-tap the preview to focus", style = MaterialTheme.typography.bodySmall, color = StudioMuted)
            Text("Digital zoom: ${"%.1f".format(zoom)}× (clamped to device capability)", color = StudioMuted)
            Slider(value = zoom, onValueChange = { zoom = it; viewModel.setZoom(it) }, valueRange = 1f..5f)
            Text("Exposure compensation: ${exposure.toInt()} (device steps)", color = StudioMuted)
            Slider(
                value = exposure,
                onValueChange = { exposure = it; viewModel.setExposure(it.toInt()) },
                valueRange = -6f..6f,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Torch", Modifier.weight(1f))
                Switch(checked = torch, onCheckedChange = { torch = it; viewModel.setTorch(it) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Mirror front camera", Modifier.weight(1f))
                Switch(checked = settings.mirrorFrontCamera, onCheckedChange = viewModel::setMirror)
            }
        }
    }
}

@Composable
fun ErrorCard(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            message,
            Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun thermalLabel(status: Int): String = when (status) {
    0 -> "Normal"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    else -> "Shutdown"
}
