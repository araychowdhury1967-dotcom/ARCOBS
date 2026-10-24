package com.mobilestreaming.studio.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mobilestreaming.studio.StudioViewModel
import com.mobilestreaming.studio.core.model.StreamPhase

enum class StudioDestination(val label: String, val icon: ImageVector) {
    Studio("Studio", Icons.Default.Home),
    Scenes("Scenes", Icons.Default.Layers),
    Sources("Sources", Icons.Default.Add),
    Overlays("Overlays", Icons.Default.Palette),
    AudioMixer("Audio Mixer", Icons.Default.Mic),
    StreamSettings("Stream Settings", Icons.Default.Tune),
    RecordingLibrary("Recording Library", Icons.Default.Folder),
    StreamChat("Stream Chat", Icons.Default.Chat),
    Settings("Settings", Icons.Default.Settings),
}

private enum class PhoneDestination(val label: String, val icon: ImageVector) {
    Studio("Studio", Icons.Default.Home),
    Scenes("Scenes", Icons.Default.Layers),
    Sources("Sources", Icons.Default.Add),
    Mixer("Mixer", Icons.Default.Mic),
    More("More", Icons.Default.MoreHoriz),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioApp(viewModel: StudioViewModel, onRequestScreenCapture: () -> Unit) {
    var currentName by rememberSaveable { mutableStateOf(StudioDestination.Studio.name) }
    var phoneTab by rememberSaveable { mutableStateOf(PhoneDestination.Studio.name) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    val current = StudioDestination.valueOf(currentName)
    val streamState by viewModel.streamState.collectAsState()
    val library by viewModel.sceneLibrary.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true && grants[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.startPreview()
        }
    }

    LaunchedEffect(Unit) {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    fun navigate(destination: StudioDestination) {
        currentName = destination.name
        showMore = false
        phoneTab = when (destination) {
            StudioDestination.Studio -> PhoneDestination.Studio.name
            StudioDestination.Scenes -> PhoneDestination.Scenes.name
            StudioDestination.Sources -> PhoneDestination.Sources.name
            StudioDestination.AudioMixer -> PhoneDestination.Mixer.name
            else -> PhoneDestination.More.name
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 900.dp
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Mobile Streaming Studio", style = MaterialTheme.typography.titleMedium)
                            Text(
                                library.activeScene.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioMuted,
                            )
                        }
                    },
                    actions = {
                        val live = streamState.phase == StreamPhase.LIVE
                        Text(
                            if (live) "● LIVE" else streamState.phase.name,
                            color = if (live) MaterialTheme.colorScheme.error else StudioMuted,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(12.dp),
                        )
                    },
                )
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        PhoneDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = phoneTab == destination.name,
                                onClick = {
                                    phoneTab = destination.name
                                    showMore = destination == PhoneDestination.More
                                    if (destination != PhoneDestination.More) {
                                        currentName = when (destination) {
                                            PhoneDestination.Mixer -> StudioDestination.AudioMixer.name
                                            else -> destination.name
                                        }
                                    }
                                },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (useRail) {
                    NavigationRail {
                        StudioDestination.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = current == destination,
                                onClick = { navigate(destination) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                label = { Text(destination.label, maxLines = 2) },
                            )
                        }
                    }
                }
                val contentModifier = Modifier.fillMaxSize()
                when {
                    !useRail && showMore -> MoreScreen(
                        current = current,
                        onNavigate = ::navigate,
                    )
                    current == StudioDestination.Studio -> StudioScreen(
                        viewModel,
                        onNavigate = ::navigate,
                        modifier = contentModifier,
                    )
                    current == StudioDestination.Scenes -> ScenesScreen(viewModel, contentModifier)
                    current == StudioDestination.Sources -> SourcesScreen(
                        viewModel, onRequestScreenCapture, contentModifier,
                    )
                    current == StudioDestination.Overlays -> OverlaysScreen(viewModel, contentModifier)
                    current == StudioDestination.AudioMixer -> AudioMixerScreen(viewModel, contentModifier)
                    current == StudioDestination.StreamSettings -> StreamSettingsScreen(viewModel, contentModifier)
                    current == StudioDestination.RecordingLibrary -> RecordingLibraryScreen(viewModel, contentModifier)
                    current == StudioDestination.StreamChat -> StreamChatScreen(viewModel, contentModifier)
                    current == StudioDestination.Settings -> SettingsScreen(viewModel, contentModifier)
                }
            }
        }
    }
}
