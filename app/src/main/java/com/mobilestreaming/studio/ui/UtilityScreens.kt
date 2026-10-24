package com.mobilestreaming.studio.ui

import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobilestreaming.studio.StudioViewModel
import com.mobilestreaming.studio.core.model.RecordingEntry
import java.text.DateFormat
import java.util.Date

@Composable
fun RecordingLibraryScreen(viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    val recordings by viewModel.recordings.collectAsState()
    val context = LocalContext.current
    var playing by remember { mutableStateOf<RecordingEntry?>(null) }
    var deleting by remember { mutableStateOf<RecordingEntry?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionHeader("Recording Library", "${recordings.size} completed recordings • ${formatBytes(recordings.sumOf { it.sizeBytes })}")
            if (recordings.isEmpty()) Text("No recordings yet. Start Recording in Studio to create an MP4 in Movies/MobileStreamingStudio.", color = StudioMuted)
        }
        items(recordings, key = { it.id }) { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${formatDuration(entry.durationMs)} • ${formatBytes(entry.sizeBytes)} • ${entry.width}×${entry.height}",
                        color = StudioMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(entry.createdAtEpochMs)),
                        color = StudioMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { playing = entry }) { Text("Play") }
                        OutlinedButton(onClick = {
                            val uri = Uri.parse(entry.contentUri)
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share recording"))
                        }) { Text("Share") }
                        TextButton(onClick = { deleting = entry }) { Text("Delete") }
                    }
                }
            }
        }
    }
    playing?.let { entry -> PlaybackDialog(entry) { playing = null } }
    deleting?.let { entry ->
        ConfirmDialog(
            title = "Delete ${entry.displayName}?",
            text = "The MP4 will be removed from device storage and recording history.",
            onDismiss = { deleting = null },
            onConfirm = { viewModel.deleteRecording(entry.id); deleting = null },
        )
    }
}

@Composable
private fun PlaybackDialog(entry: RecordingEntry, onDismiss: () -> Unit) {
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.displayName) },
        text = {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(Uri.parse(entry.contentUri))
                        setMediaController(MediaController(context))
                        setOnPreparedListener { start() }
                        videoView = this
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    DisposableEffect(entry.id) { onDispose { videoView?.stopPlayback() } }
}

@Composable
fun StreamChatScreen(viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    val information by viewModel.streamInformation.collectAsState()
    val stream by viewModel.streamState.collectAsState()
    var title by remember(information) { mutableStateOf(information.title) }
    var description by remember(information) { mutableStateOf(information.description) }
    var category by remember(information) { mutableStateOf(information.category) }
    var destination by remember(information) { mutableStateOf(information.destinationName) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeader("Stream Chat & Information", "Metadata is local until an authorized platform integration is connected") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Stream title") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category / game") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = destination, onValueChange = { destination = it }, label = { Text("Destination name") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        viewModel.saveStreamInformation(
                            information.copy(title = title, description = description, category = category, destinationName = destination),
                        )
                    }) { Text("Save stream information") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Destination: ${information.destinationName}", fontWeight = FontWeight.SemiBold)
                    Text("Status: ${stream.phase.name}")
                    Text("Viewer metrics: unavailable without an authorized platform API", color = StudioMuted)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No chat provider connected", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Generic RTMP/RTMPS publishes audio and video; it does not provide chat or viewer metrics. Connect an official platform API with an approved authentication flow and scopes to enable those features.",
                        color = StudioMuted,
                    )
                    Text("No sample messages or simulated viewer counts are shown.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
