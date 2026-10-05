package com.creep.screenrecorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.creep.screenrecorder.data.CaptureAudioMode
import com.creep.screenrecorder.data.CaptureKind
import com.creep.screenrecorder.data.MediaCapture
import com.creep.screenrecorder.data.ProjectionScope
import com.creep.screenrecorder.data.SaveLocationMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val Night = Color(0xFF090D10)
private val Panel = Color(0xFF121A1F)
private val PanelRaised = Color(0xFF192329)
private val Edge = Color(0xFF2A383D)
private val Lime = Color(0xFFB7F36B)
private val Ink = Color(0xFF10170D)
private val SoftWhite = Color(0xFFF1F6F2)
private val Muted = Color(0xFF98A7A1)
private val Red = Color(0xFFFF6671)

@Composable
internal fun ScreenKitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Lime,
            onPrimary = Ink,
            secondary = Color(0xFF8FE0C1),
            background = Night,
            surface = Panel,
            onSurface = SoftWhite,
            onBackground = SoftWhite,
            error = Red,
        ),
        content = content,
    )
}

@Composable
internal fun MainScreen(
    state: CaptureUiState,
    showOverlayWelcome: Boolean,
    onDismissOverlayWelcome: () -> Unit,
    onEnableOverlay: () -> Unit,
    onDisableOverlay: () -> Unit,
    onTakeScreenshot: () -> Unit,
    onToggleScreenRecording: () -> Unit,
    onToggleCameraRecording: () -> Unit,
    onAudioModeSelected: (CaptureAudioMode) -> Unit,
    onToggleCameraOverlay: (Boolean) -> Unit,
    onCameraFacingSelected: (CameraFacing) -> Unit,
    onProjectionScopeSelected: (ProjectionScope) -> Unit,
    onSaveModeSelected: (SaveLocationMode) -> Unit,
    onVolumeSelected: (String) -> Unit,
    onPickFolder: () -> Unit,
    onOpenCapture: (MediaCapture) -> Unit,
) {
    ScreenKitTheme {
        Box(Modifier.fillMaxSize().background(Night)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                Header(state)
                HeroCard()
                PermissionsCard(state)
                CaptureActions(
                    state = state,
                    onTakeScreenshot = onTakeScreenshot,
                    onToggleScreenRecording = onToggleScreenRecording,
                    onToggleCameraRecording = onToggleCameraRecording,
                    onProjectionScopeSelected = onProjectionScopeSelected,
                )
                CameraCard(state, onToggleCameraOverlay, onCameraFacingSelected)
                SoundCard(state, onAudioModeSelected)
                SaveLocationCard(state, onSaveModeSelected, onVolumeSelected, onPickFolder)
                FloatingControlsCard(state, onEnableOverlay, onDisableOverlay)
                RecentCaptures(state.recentCaptures, onOpenCapture)
                PrivacyNote()
                Spacer(Modifier.height(8.dp))
            }

            if (showOverlayWelcome) {
                AlertDialog(
                    onDismissRequest = onDismissOverlayWelcome,
                    containerColor = PanelRaised,
                    title = {
                        Text("Enable the floating control?", color = SoftWhite, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Text(
                            "ScreenKit can show a small, draggable bubble over other apps. It stays visible while enabled and is used only for capture shortcuts.",
                            color = Muted,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = onEnableOverlay) {
                            Text("Continue", color = Lime, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissOverlayWelcome) {
                            Text("Not now", color = Muted)
                        }
                    },
                    properties = DialogProperties(usePlatformDefaultWidth = true),
                )
            }
        }
    }
}

@Composable
private fun Header(state: CaptureUiState) {
    val recording = state.screenRecording || state.cameraRecording
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Lime),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = Ink, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.padding(start = 11.dp).weight(1f)) {
            Text("SCREENKIT", color = SoftWhite, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
            Text("Capture studio", color = Muted, fontSize = 12.sp)
        }
        Surface(
            color = if (recording) Color(0xFF321A20) else Panel,
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (recording) Color(0xFF70414A) else Edge),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (recording) Red else Lime))
                Text(
                    text = if (recording) "RECORDING" else if (state.screenSessionActive || state.cameraSessionActive) "CAPTURING" else "READY",
                    modifier = Modifier.padding(start = 7.dp),
                    color = if (recording) Red else Lime,
                    fontSize = 9.sp,
                    letterSpacing = 0.7.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun HeroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30403C)),
    ) {
        Column(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(Color(0xFF20312D), Color(0xFF121A1F))))
                .padding(21.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Lime))
                Text("  LOCAL-FIRST CAPTURE", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            }
            Text(
                "Capture your screen.\nKeep every detail.",
                modifier = Modifier.padding(top = 16.dp),
                color = SoftWhite,
                fontSize = 29.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "Save crisp screenshots, record the display, or make a camera video — all on this device.",
                modifier = Modifier.padding(top = 10.dp),
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                SmallTag("SAVED ON DEVICE")
                SmallTag("NO CLOUD UPLOAD")
            }
        }
    }
}

@Composable
private fun SmallTag(text: String) {
    Surface(color = Color(0x332D4238), shape = CircleShape) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp), color = Color(0xFFD3E2D9), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp)
    }
}

@Composable
private fun PermissionsCard(state: CaptureUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PERMISSION STATUS", color = Lime, fontSize = 10.sp, letterSpacing = 1.15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("YOU CONTROL EACH ONE", color = Muted, fontSize = 8.sp, letterSpacing = 0.4.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                PermissionPill("Overlay", state.overlayPermission, Modifier.weight(1f))
                PermissionPill("Camera", state.cameraPermission, Modifier.weight(1f))
                PermissionPill("Mic", state.microphonePermission, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                PermissionPill("Notifications", state.notificationPermission, Modifier.weight(1f))
                PermissionPill("Storage", state.storagePermission, Modifier.weight(1f))
                PermissionPill("Consent", false, Modifier.weight(1f), labelWhenOff = "Per capture")
            }
        }
    }
}

@Composable
private fun PermissionPill(name: String, granted: Boolean, modifier: Modifier = Modifier, labelWhenOff: String = "Needed") {
    Surface(
        modifier = modifier,
        color = if (granted) Color(0xFF17231C) else Color(0xFF20252A),
        shape = RoundedCornerShape(11.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (granted) Lime else Color(0xFF68736F)))
            Text(
                text = if (granted) name else labelWhenOff,
                modifier = Modifier.padding(start = 5.dp),
                color = if (granted) Color(0xFFD8E2DB) else Muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CaptureActions(
    state: CaptureUiState,
    onTakeScreenshot: () -> Unit,
    onToggleScreenRecording: () -> Unit,
    onToggleCameraRecording: () -> Unit,
    onProjectionScopeSelected: (ProjectionScope) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading("QUICK ACTIONS", "Capture the screen or camera")
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
        ) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(Icons.Filled.FiberManualRecord, Color(0xFFFF6671), "rec")
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Screen recording", color = SoftWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("H.264 MP4 · up to 1080p · 30 fps", color = Muted, fontSize = 11.sp)
                    }
                    if (state.screenRecording) {
                        RecordingTimer(state.screenRecordingStartedAt)
                    }
                }
                ActionButton(
                    label = when {
                        state.screenRecording -> "Stop and save recording"
                        state.screenSessionActive -> "Screen capture in progress…"
                        else -> "Start screen recording"
                    },
                    icon = if (state.screenRecording) Icons.Filled.Stop else Icons.Filled.Videocam,
                    enabled = !state.cameraRecording && (!state.screenSessionActive || state.screenRecording),
                    emphasized = state.screenRecording,
                    onClick = onToggleScreenRecording,
                )
                HorizontalDivider(color = Edge.copy(alpha = 0.7f))
                CaptureScopeRow(state, onProjectionScopeSelected)
                HorizontalDivider(color = Edge.copy(alpha = 0.7f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(Icons.Filled.PhotoCamera, Lime, "shot")
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Full-resolution screenshot", color = SoftWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("PNG · Android approval required each time", color = Muted, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = onTakeScreenshot,
                        enabled = !state.screenSessionActive && !state.cameraRecording,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime),
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Capture", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
        ) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(Icons.Filled.CameraAlt, Color(0xFF8FE0C1), "camera")
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Camera recording", color = SoftWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("CameraX · front or back · continues in foreground", color = Muted, fontSize = 11.sp)
                    }
                    if (state.cameraRecording) {
                        RecordingTimer(state.cameraRecordingStartedAt)
                    }
                }
                ActionButton(
                    label = if (state.cameraRecording) "Stop and save camera video" else "Start camera recording",
                    icon = if (state.cameraRecording) Icons.Filled.Stop else Icons.Filled.Videocam,
                    enabled = !state.screenSessionActive,
                    emphasized = state.cameraRecording,
                    onClick = onToggleCameraRecording,
                )
            }
        }
    }
}

@Composable
private fun SoundCard(
    state: CaptureUiState,
    onAudioModeSelected: (CaptureAudioMode) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("SOUND", "What goes into the recording")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                CaptureAudioMode.values().forEach { mode ->
                    AudioModeChip(
                        mode = mode,
                        selected = state.audioMode == mode,
                        enabled = !mode.usesDeviceAudio || state.deviceAudioSupported,
                        onClick = { onAudioModeSelected(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                text = when {
                    state.audioMode.usesDeviceAudio && !state.deviceAudioSupported ->
                        "Device audio needs Android 10 or newer on this phone."
                    state.audioMode.usesDeviceAudio ->
                        "Device audio records what this phone plays. Apps that opt out of capture stay silent: calls and most streaming services block it by design."
                    state.audioMode == CaptureAudioMode.NONE ->
                        "Recordings have no sound. Pick Microphone for your voice, device audio for the phone's own sound, or both."
                    else ->
                        "The microphone is recorded. Screen-recording audio is fixed when the MP4 starts; camera videos can be muted while recording."
                },
                color = Muted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

@Composable
private fun AudioModeChip(
    mode: CaptureAudioMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        color = if (selected) Color(0xFF1D2A1F) else PanelRaised,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Lime else Edge),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = mode.label,
                color = if (!enabled) Muted else if (selected) Lime else SoftWhite,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    !enabled -> "Android 10+"
                    mode.usesDeviceAudio && mode.usesMicrophone -> "mic + device"
                    mode.usesDeviceAudio -> "phone sound"
                    mode.usesMicrophone -> "your voice"
                    else -> "silent"
                },
                color = Muted,
                fontSize = 8.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CaptureScopeRow(
    state: CaptureUiState,
    onProjectionScopeSelected: (ProjectionScope) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color(0xFF8FE0C1), modifier = Modifier.size(21.dp))
        Column(Modifier.padding(start = 9.dp).weight(1f)) {
            Text("Record the whole screen", color = SoftWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (state.projectionScope == ProjectionScope.ENTIRE_SCREEN) {
                    "Skips Android 14's “single app” option, which freezes the video when you switch apps"
                } else {
                    "Android 14+ will also offer “a single app”; the video freezes once you leave it"
                },
                color = Muted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
        }
        Switch(
            checked = state.projectionScope == ProjectionScope.ENTIRE_SCREEN,
            onCheckedChange = { whole ->
                onProjectionScopeSelected(
                    if (whole) ProjectionScope.ENTIRE_SCREEN else ProjectionScope.USER_CHOICE,
                )
            },
        )
    }
}

@Composable
private fun SaveLocationCard(
    state: CaptureUiState,
    onSaveModeSelected: (SaveLocationMode) -> Unit,
    onVolumeSelected: (String) -> Unit,
    onPickFolder: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("SAVE LOCATION", "Choose where screenshots and videos go")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                SaveModeChip(
                    label = "Gallery",
                    mode = SaveLocationMode.MEDIA_DEFAULT,
                    selected = state.saveMode,
                    modifier = Modifier.weight(1f),
                    onSelected = onSaveModeSelected,
                )
                SaveModeChip(
                    label = "SD card",
                    mode = SaveLocationMode.MEDIA_VOLUME,
                    selected = state.saveMode,
                    modifier = Modifier.weight(1f),
                    onSelected = onSaveModeSelected,
                )
                SaveModeChip(
                    label = "Folder",
                    mode = SaveLocationMode.CUSTOM_FOLDER,
                    selected = state.saveMode,
                    modifier = Modifier.weight(1f),
                    onSelected = onSaveModeSelected,
                )
            }
            Surface(color = PanelRaised, shape = RoundedCornerShape(12.dp)) {
                Text(
                    text = state.saveLocationLabel.ifBlank { "Device gallery" },
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
                    color = Color(0xFFD8E2DB),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.saveMode == SaveLocationMode.MEDIA_VOLUME && state.volumes.size > 1) {
                VolumePicker(state, onVolumeSelected)
            }
            if (state.saveMode == SaveLocationMode.CUSTOM_FOLDER) {
                OutlinedButton(
                    onClick = onPickFolder,
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime),
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.saveLocationLabel.isBlank()) "Choose folder" else "Change folder",
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    "Screenshots and videos are written straight into that folder, including on a removable card. While a recording runs ScreenKit uses free cache space and copies the finished file across.",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
            if (state.saveMode == SaveLocationMode.MEDIA_VOLUME) {
                Text(
                    "Kept in the media library on the chosen volume, so your gallery still indexes it. On Android 9 and older, pick a folder instead.",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun SaveModeChip(
    label: String,
    mode: SaveLocationMode,
    selected: SaveLocationMode,
    modifier: Modifier,
    onSelected: (SaveLocationMode) -> Unit,
) {
    val isSelected = selected == mode
    Surface(
        onClick = { onSelected(mode) },
        modifier = modifier,
        color = if (isSelected) Color(0xFF1D2A1F) else PanelRaised,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) Lime else Edge),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = 11.dp),
            color = if (isSelected) Lime else SoftWhite,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VolumePicker(state: CaptureUiState, onVolumeSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = state.volumes.firstOrNull { it.name == state.selectedVolume }?.label ?: "Choose a volume"
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime),
        ) {
            Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.volumes.forEach { volume ->
                DropdownMenuItem(
                    text = { Text(if (volume.removable) "${volume.label} (removable)" else volume.label) },
                    onClick = {
                        expanded = false
                        onVolumeSelected(volume.name)
                    },
                )
            }
        }
    }
}

@Composable
private fun CameraCard(
    state: CaptureUiState,
    onToggleCameraOverlay: (Boolean) -> Unit,
    onCameraFacingSelected: (CameraFacing) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionHeading("CAMERA OPTIONS", "Choose the lens and preview")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FlipCameraAndroid, contentDescription = null, tint = Lime, modifier = Modifier.size(21.dp))
                Text("Camera lens", modifier = Modifier.padding(start = 9.dp).weight(1f), color = SoftWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                CameraDropdown(state.cameraFacing, !state.cameraRecording, onCameraFacingSelected)
            }
            HorizontalDivider(color = Edge.copy(alpha = 0.7f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = Color(0xFF8FE0C1), modifier = Modifier.size(21.dp))
                Column(Modifier.padding(start = 9.dp).weight(1f)) {
                    Text("Show camera overlay", color = SoftWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Draggable live preview over other apps", color = Muted, fontSize = 10.sp)
                }
                Switch(
                    checked = state.cameraOverlayEnabled,
                    onCheckedChange = onToggleCameraOverlay,
                    enabled = !state.cameraRecording,
                )
            }
            if (state.cameraPreviewVisible) {
                Text(
                    "Camera preview is live. The bubble's Flip button switches lens while the preview or recording runs.",
                    color = Color(0xFFB5C5BC),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun CameraDropdown(
    facing: CameraFacing,
    enabled: Boolean,
    onSelected: (CameraFacing) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime),
        ) {
            Text(if (facing == CameraFacing.FRONT) "Front camera" else "Back camera", fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Back camera") },
                onClick = { expanded = false; onSelected(CameraFacing.BACK) },
            )
            DropdownMenuItem(
                text = { Text("Front camera") },
                onClick = { expanded = false; onSelected(CameraFacing.FRONT) },
            )
        }
    }
}

@Composable
private fun FloatingControlsCard(
    state: CaptureUiState,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(21.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(45.dp).clip(CircleShape)
                    .background(if (state.overlayEnabled) Lime else PanelRaised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null,
                    tint = if (state.overlayEnabled) Ink else Muted, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Floating control", color = SoftWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (state.overlayEnabled) "Bubble is available over your apps" else "Enable a draggable capture shortcut",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            if (state.overlayEnabled) {
                OutlinedButton(onClick = onDisable, shape = RoundedCornerShape(13.dp)) {
                    Text("Hide", color = SoftWhite)
                }
            } else {
                Button(
                    onClick = onEnable,
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
                ) {
                    Text(if (state.overlayPermission) "Enable" else "Allow", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RecentCaptures(items: List<MediaCapture>, onOpen: (MediaCapture) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR LIBRARY", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("Recent captures", color = SoftWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text("ON DEVICE", color = Muted, fontSize = 9.sp, letterSpacing = 0.7.sp)
        }
        if (items.isEmpty()) {
            Card(
                shape = RoundedCornerShape(19.dp),
                colors = CardDefaults.cardColors(containerColor = Panel),
                border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
            ) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = Lime, modifier = Modifier.size(26.dp))
                    Text("Nothing saved yet", modifier = Modifier.padding(top = 9.dp), color = SoftWhite, fontWeight = FontWeight.Bold)
                    Text("Your screenshots and MP4 videos will appear here.", color = Muted, fontSize = 12.sp)
                }
            }
        } else {
            items.forEach { capture -> CaptureRow(capture, onOpen) }
        }
    }
}

@Composable
private fun CaptureRow(item: MediaCapture, onOpen: (MediaCapture) -> Unit) {
    Card(
        onClick = { onOpen(item) },
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = androidx.compose.foundation.BorderStroke(1.dp, Edge),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionIcon(
                if (item.kind.isVideo) Icons.Filled.Videocam else Icons.Filled.PhotoCamera,
                if (item.kind == CaptureKind.CAMERA_RECORDING) Color(0xFF8FE0C1) else Lime,
                if (item.kind.isVideo) "video" else "image",
            )
            Column(Modifier.padding(start = 11.dp).weight(1f)) {
                Text(item.title, color = SoftWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val kind = when (item.kind) {
                    CaptureKind.SCREENSHOT -> "PNG · Full resolution"
                    CaptureKind.SCREEN_RECORDING -> "MP4 · Screen"
                    CaptureKind.CAMERA_RECORDING -> "MP4 · Camera"
                }
                Text("$kind · ${formatBytes(item.sizeBytes)}", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatCaptureDate(item.dateAddedSeconds), color = Color(0xFF72817A), fontSize = 10.sp)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = "Open capture", tint = Muted)
        }
    }
}

@Composable
private fun PrivacyNote() {
    Surface(color = Color(0xFF10171B), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF253337))) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = Lime, modifier = Modifier.size(19.dp))
            Text(
                "Android asks you to approve every screen session. Secure/DRM windows come out blank and calls or apps that opt out of audio capture stay silent — no normal app can record those.",
                modifier = Modifier.padding(start = 10.dp),
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun SectionHeading(eyebrow: String, subtitle: String) {
    Column {
        Text(eyebrow, color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, tint: Color, label: String) {
    Box(
        modifier = Modifier.size(45.dp).clip(RoundedCornerShape(14.dp))
            .background(if (label == "rec") Color(0xFF2A1B20) else PanelRaised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(53.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (emphasized) Color(0xFFB7414D) else Lime,
            contentColor = if (emphasized) Color.White else Ink,
            disabledContainerColor = PanelRaised,
            disabledContentColor = Muted,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(9.dp))
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun RecordingTimer(startedAt: Long) {
    var now by remember(startedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (startedAt > 0) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val seconds = ((now - startedAt).coerceAtLeast(0L) / 1_000L)
    Surface(color = Color(0xFF2A1B20), shape = CircleShape) {
        Text(
            String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            color = Red,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1_048_576L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_024.0)
    bytes < 1_073_741_824L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    else -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_073_741_824.0)
}

private fun formatCaptureDate(epochSeconds: Long): String =
    SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(Date(epochSeconds * 1_000L))
