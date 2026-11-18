package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.model.CoachMode
import com.example.ui.theme.BroadcastSlate
import com.example.ui.theme.CrimsonLive
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricVolt
import com.example.ui.theme.ElevatedCard
import com.example.ui.theme.NeonMint
import com.example.ui.theme.ObsidianInk
import com.example.ui.theme.SteelOutline
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.AiStudioUiState
import com.example.ui.viewmodel.AsyncOpState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViralCoachSheet(
    aiState: AiStudioUiState,
    onSelectMode: (CoachMode) -> Unit,
    onSendMessage: (String) -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartVoice()
    }

    LaunchedEffect(aiState.transcription) {
        val t = aiState.transcription
        if (t is AsyncOpState.Success && t.data.isNotBlank()) {
            draft = t.data
        }
    }

    LaunchedEffect(aiState.coachMessages.size) {
        if (aiState.coachMessages.isNotEmpty()) {
            listState.animateScrollToItem(aiState.coachMessages.lastIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BroadcastSlate
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ElectricVolt)
                Column {
                    Text(
                        text = "Viral Growth Coach",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = aiState.coachMode.modelLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CoachMode.entries.forEach { mode ->
                    val selected = aiState.coachMode == mode
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectMode(mode) },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricVolt,
                            selectedLabelColor = ObsidianInk
                        ),
                        modifier = Modifier.testTag("coach_mode_${mode.name.lowercase()}")
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 340.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(aiState.coachMessages, key = { it.id }) { msg ->
                    val isUser = msg.role == "user"
                    val align = if (isUser) Alignment.End else Alignment.Start
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = align
                    ) {
                        Surface(
                            color = if (isUser) ElectricVolt.copy(alpha = 0.16f) else ElevatedCard,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isUser) ElectricVolt.copy(alpha = 0.45f) else SteelOutline
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                if (msg.modelBadge.isNotBlank() && !isUser) {
                                    Text(
                                        text = msg.modelBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberCyan
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    text = msg.text.ifBlank { "..." },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                                if (msg.groundingSources.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "Sources: ${msg.groundingSources.take(3).joinToString(" • ")}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeonMint
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (aiState.isCoachStreaming || aiState.transcription is AsyncOpState.Loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = ElectricVolt
                )
            }

            if (!aiState.coachError.isNullOrBlank()) {
                Text(
                    text = aiState.coachError,
                    color = CrimsonLive,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Ask for a Frame-1 hook, tags, or Shorts pacing...", color = TextMuted) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("coach_input"),
                    maxLines = 3
                )

                IconButton(
                    onClick = {
                        if (aiState.isRecordingMic) {
                            onStopVoice()
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) onStartVoice()
                            else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (aiState.isRecordingMic) CrimsonLive else ElevatedCard
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("coach_mic_button")
                ) {
                    Icon(
                        imageVector = if (aiState.isRecordingMic) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (aiState.isRecordingMic) "Stop voice recording" else "Record voice prompt",
                        tint = TextPrimary
                    )
                }

                IconButton(
                    onClick = {
                        val toSend = draft.trim()
                        if (toSend.isNotEmpty()) {
                            onSendMessage(toSend)
                            draft = ""
                        }
                    },
                    enabled = !aiState.isCoachStreaming && draft.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = ElectricVolt,
                        contentColor = ObsidianInk
                    ),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("coach_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send coach message"
                    )
                }
            }
        }
    }
}
