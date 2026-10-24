package com.mobilestreaming.studio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mobilestreaming.studio.OverlayPreset
import com.mobilestreaming.studio.StudioViewModel
import com.mobilestreaming.studio.core.model.Scene
import com.mobilestreaming.studio.core.model.SourceCrop
import com.mobilestreaming.studio.core.model.SourceKind
import com.mobilestreaming.studio.core.model.StreamPhase
import com.mobilestreaming.studio.core.model.VisualSource

@Composable
fun ScenesScreen(viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    val library by viewModel.sceneLibrary.collectAsState()
    var addScene by remember { mutableStateOf(false) }
    var renameScene by remember { mutableStateOf<Scene?>(null) }
    var deleteScene by remember { mutableStateOf<Scene?>(null) }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionHeader("Scenes", "Switch instantly during a broadcast. The encoder stays active.")
            Button(onClick = { addScene = true }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Create scene")
            }
        }
        items(library.scenes, key = { it.id }) { scene ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(scene.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${scene.sources.size} sources", color = StudioMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (scene.id == library.activeSceneId) {
                            FilterChip(selected = true, onClick = {}, label = { Text("Active") })
                        } else {
                            OutlinedButton(onClick = { viewModel.switchScene(scene.id) }) { Text("Switch") }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { renameScene = scene }) { Icon(Icons.Default.Edit, "Rename scene") }
                        IconButton(onClick = { viewModel.duplicateScene(scene.id) }) { Icon(Icons.Default.ContentCopy, "Duplicate scene") }
                        IconButton(onClick = { viewModel.moveScene(scene.id, -1) }, enabled = library.scenes.first().id != scene.id) {
                            Icon(Icons.Default.ArrowUpward, "Move scene up")
                        }
                        IconButton(onClick = { viewModel.moveScene(scene.id, 1) }, enabled = library.scenes.last().id != scene.id) {
                            Icon(Icons.Default.ArrowDownward, "Move scene down")
                        }
                        IconButton(onClick = { deleteScene = scene }, enabled = library.scenes.size > 1) {
                            Icon(Icons.Default.Delete, "Delete scene")
                        }
                    }
                }
            }
        }
    }
    if (addScene) {
        TextInputDialog(
            title = "Create scene",
            initial = "New scene",
            confirm = "Create",
            onDismiss = { addScene = false },
            onConfirm = { viewModel.addScene(it); addScene = false },
        )
    }
    renameScene?.let { scene ->
        TextInputDialog(
            title = "Rename scene",
            initial = scene.name,
            confirm = "Rename",
            onDismiss = { renameScene = null },
            onConfirm = { viewModel.renameScene(scene.id, it); renameScene = null },
        )
    }
    deleteScene?.let { scene ->
        ConfirmDialog(
            title = "Delete ${scene.name}?",
            text = "Its sources and overlay layout will be removed. This cannot be undone.",
            onDismiss = { deleteScene = null },
            onConfirm = { viewModel.deleteScene(scene.id); deleteScene = null },
        )
    }
}

@Composable
fun SourcesScreen(viewModel: StudioViewModel, onRequestScreenCapture: () -> Unit, modifier: Modifier = Modifier) {
    val library by viewModel.sceneLibrary.collectAsState()
    val scene = library.activeScene
    var browserDialog by remember { mutableStateOf(false) }
    var deleteSource by remember { mutableStateOf<VisualSource?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.addImageSource(uri)
    }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionHeader("Sources", "${scene.name} • Top layers appear first")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = viewModel::addTextSource, label = { Text("Text") })
                AssistChip(onClick = { imagePicker.launch(arrayOf("image/*")) }, label = { Text("Image") })
                AssistChip(onClick = { viewModel.addBasicSource(SourceKind.CAMERA) }, label = { Text("Camera") })
                AssistChip(
                    onClick = { viewModel.addBasicSource(SourceKind.SCREEN); onRequestScreenCapture() },
                    label = { Text("Screen") },
                )
                AssistChip(onClick = { browserDialog = true }, label = { Text("Browser") })
                AssistChip(onClick = { viewModel.addBasicSource(SourceKind.SOLID_COLOR) }, label = { Text("Color") })
            }
            if (scene.sources.any { it.kind == SourceKind.SCREEN }) {
                TextButton(onClick = viewModel::stopScreenCapture) { Text("Stop screen capture securely") }
            }
        }
        items(scene.sources.asReversed(), key = { it.id }) { source ->
            SourceRow(
                source = source,
                selected = source.id == library.selectedSourceId,
                onSelect = { viewModel.selectSource(source.id) },
                onVisibility = { viewModel.updateSource(source.id) { s -> s.copy(visible = !s.visible) } },
                onLock = { viewModel.updateSource(source.id) { s -> s.copy(locked = !s.locked) } },
                onMove = { viewModel.moveSource(source.id, it) },
                onDelete = { deleteSource = source },
            )
        }
        item { HorizontalDivider() }
        val selected = scene.sources.firstOrNull { it.id == library.selectedSourceId }
        if (selected != null) {
            item { SourceEditor(viewModel, selected) }
        } else {
            item { Text("Select a source to edit its properties or manipulate it in Studio preview.", color = StudioMuted) }
        }
    }
    if (browserDialog) {
        BrowserSourceDialog(
            onDismiss = { browserDialog = false },
            onConfirm = { url, js -> viewModel.addBrowserSource(url, js); browserDialog = false },
        )
    }
    deleteSource?.let { source ->
        ConfirmDialog(
            title = "Delete ${source.name}?",
            text = "The source will be removed from this scene.",
            onDismiss = { deleteSource = null },
            onConfirm = { viewModel.deleteSource(source.id); deleteSource = null },
        )
    }
}

@Composable
private fun SourceRow(
    source: VisualSource,
    selected: Boolean,
    onSelect: () -> Unit,
    onVisibility: () -> Unit,
    onLock: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Card(if (selected) Modifier.fillMaxWidth().clickable(onClick = onSelect) else Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(source.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                Text(source.kind.name, style = MaterialTheme.typography.labelSmall, color = StudioMuted)
            }
            IconButton(onClick = onVisibility) { Icon(if (source.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff, "Visibility") }
            IconButton(onClick = onLock) { Icon(if (source.locked) Icons.Default.Lock else Icons.Default.LockOpen, "Lock") }
            IconButton(onClick = { onMove(1) }) { Icon(Icons.Default.ArrowUpward, "Raise layer") }
            IconButton(onClick = { onMove(-1) }) { Icon(Icons.Default.ArrowDownward, "Lower layer") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete source") }
        }
    }
}

@Composable
private fun SourceEditor(viewModel: StudioViewModel, source: VisualSource) {
    var name by remember(source.id) { mutableStateOf(source.name) }
    var text by remember(source.id) { mutableStateOf(source.text) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Source properties", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            if (source.kind != SourceKind.IMAGE && source.kind != SourceKind.LOGO && source.kind != SourceKind.CAMERA &&
                source.kind != SourceKind.SCREEN && source.kind != SourceKind.BROWSER && source.kind != SourceKind.SOLID_COLOR &&
                source.kind != SourceKind.CLOCK && source.kind != SourceKind.DATE
            ) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
            Button(
                onClick = { viewModel.updateSource(source.id) { it.copy(name = name, text = text) } },
                enabled = name.isNotBlank(),
            ) { Text("Save text and name") }
            PropertySlider("Opacity", source.opacity, 0f..1f, "%.0f%%", 100f) { value ->
                viewModel.updateSource(source.id) { it.copy(opacity = value) }
            }
            PropertySlider("Width", source.transform.width, 0.05f..2f, "%.2f") { value ->
                viewModel.updateSource(source.id) { it.copy(transform = it.transform.copy(width = value).constrained()) }
            }
            PropertySlider("Height", source.transform.height, 0.05f..2f, "%.2f") { value ->
                viewModel.updateSource(source.id) { it.copy(transform = it.transform.copy(height = value).constrained()) }
            }
            PropertySlider("Rotation", source.transform.rotationDegrees, 0f..360f, "%.0f°") { value ->
                viewModel.updateSource(source.id) { it.copy(transform = it.transform.copy(rotationDegrees = value).constrained()) }
            }
            Text("Crop", fontWeight = FontWeight.SemiBold)
            CropSlider("Left", source.crop.left) { value -> viewModel.updateSource(source.id) { s -> s.copy(crop = s.crop.copy(left = value).constrained()) } }
            CropSlider("Top", source.crop.top) { value -> viewModel.updateSource(source.id) { s -> s.copy(crop = s.crop.copy(top = value).constrained()) } }
            CropSlider("Right", source.crop.right) { value -> viewModel.updateSource(source.id) { s -> s.copy(crop = s.crop.copy(right = value).constrained()) } }
            CropSlider("Bottom", source.crop.bottom) { value -> viewModel.updateSource(source.id) { s -> s.copy(crop = s.crop.copy(bottom = value).constrained()) } }
            if (source.kind != SourceKind.CAMERA && source.kind != SourceKind.SCREEN && source.kind != SourceKind.IMAGE && source.kind != SourceKind.LOGO) {
                PropertySlider("Font scale", source.fontScale, 0.25f..4f, "%.2f×") { value ->
                    viewModel.updateSource(source.id) { it.copy(fontScale = value) }
                }
                Text("Text color")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("White" to 0xFFFFFFFF, "Teal" to 0xFF2DD4BF, "Orange" to 0xFFF97316, "Red" to 0xFFEF4444, "Black" to 0xFF000000)
                        .forEach { (label, color) ->
                            FilterChip(
                                selected = source.foregroundColor == color,
                                onClick = { viewModel.updateSource(source.id) { it.copy(foregroundColor = color) } },
                                label = { Text(label) },
                            )
                        }
                }
                Text("Background")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("Clear" to 0L, "Dark" to 0xCC101820, "Teal" to 0xE60F766E, "Red" to 0xE6B91C1C)
                        .forEach { (label, color) ->
                            FilterChip(
                                selected = source.backgroundColor == color,
                                onClick = { viewModel.updateSource(source.id) { it.copy(backgroundColor = color) } },
                                label = { Text(label) },
                            )
                        }
                }
            }
            if (source.kind == SourceKind.CAMERA) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mirror this camera source", Modifier.weight(1f))
                    Switch(checked = source.mirrored, onCheckedChange = { checked -> viewModel.updateSource(source.id) { it.copy(mirrored = checked) } })
                }
            }
            if (source.kind == SourceKind.BROWSER) {
                var url by remember(source.id) { mutableStateOf(source.browserUrl.orEmpty()) }
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("HTTPS overlay URL") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow JavaScript (no app bridges)", Modifier.weight(1f))
                    Switch(
                        checked = source.browserJavaScript,
                        onCheckedChange = { checked -> viewModel.updateSource(source.id) { it.copy(browserJavaScript = checked) } },
                    )
                }
                Button(onClick = { viewModel.updateSource(source.id) { it.copy(browserUrl = url).also { candidate -> candidate.browserUrlValidated() } } }) {
                    Text("Apply secure URL")
                }
            }
        }
    }
}

@Composable
private fun PropertySlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    multiplier: Float = 1f,
    onFinished: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label: ${format.format(current * multiplier)}", style = MaterialTheme.typography.bodySmall, color = StudioMuted)
        Slider(value = current, onValueChange = { current = it }, onValueChangeFinished = { onFinished(current) }, valueRange = range)
    }
}

@Composable
private fun CropSlider(label: String, value: Float, onFinished: (Float) -> Unit) =
    PropertySlider(label, value, 0f..0.45f, "%.0f%%", 100f, onFinished)

@Composable
fun OverlaysScreen(viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    val config by viewModel.streamConfig.collectAsState()
    val stream by viewModel.streamState.collectAsState()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.addImageSource(uri, logo = true)
    }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionHeader("Overlay Studio", "Transparent graphics rendered into preview, RTMP and MP4")
            Text("Canvas aspect ratio", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("16:9" to (1280 to 720), "9:16" to (720 to 1280), "1:1" to (960 to 960)).forEach { (label, size) ->
                    FilterChip(
                        label = { Text(label) },
                        selected = config.width == size.first && config.height == size.second,
                        enabled = !stream.isSessionActive,
                        onClick = {
                            viewModel.saveStreamConfig(
                                config.copy(width = size.first, height = size.second, qualityPreset = com.mobilestreaming.studio.core.model.QualityPreset.CUSTOM),
                            )
                        },
                    )
                }
            }
            if (stream.isSessionActive) Text("Stop the stream to change the encoded canvas.", color = StudioMuted)
        }
        items(OverlayPreset.entries.filter { it != OverlayPreset.LOGO }) { preset ->
            Card(Modifier.fillMaxWidth().clickable { viewModel.addOverlay(preset) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(preset.label, style = MaterialTheme.typography.titleMedium)
                    Text("Add to current scene", color = StudioMuted)
                }
            }
        }
        item {
            Button(onClick = { imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }) {
                Text("Import transparent logo or image")
            }
            Text(
                "Built-in presets and every customized overlay are saved with scenes. Import/export is available in Settings.",
                color = StudioMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun MoreScreen(current: StudioDestination, onNavigate: (StudioDestination) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionHeader("Studio sections", "Current: ${current.label}") }
        items(listOf(StudioDestination.Overlays, StudioDestination.StreamSettings, StudioDestination.RecordingLibrary, StudioDestination.StreamChat, StudioDestination.Settings)) { destination ->
            Card(Modifier.fillMaxWidth().clickable { onNavigate(destination) }) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(destination.icon, null); Spacer(Modifier.width(16.dp)); Text(destination.label)
                }
            }
        }
    }
}

@Composable
private fun BrowserSourceDialog(onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    var url by remember { mutableStateOf("https://") }
    var javaScript by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add browser overlay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("HTTPS URL") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable JavaScript", Modifier.weight(1f)); Switch(checked = javaScript, onCheckedChange = { javaScript = it })
                }
                Text("File access, content access, cookies, mixed content and cross-origin navigation are blocked.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(url, javaScript) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
