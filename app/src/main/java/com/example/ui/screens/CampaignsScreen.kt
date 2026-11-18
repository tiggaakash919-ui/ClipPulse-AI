package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.ClippingCampaign
import com.example.data.model.PublishedShort
import com.example.data.model.WatermarkCorner
import com.example.ui.theme.BroadcastSlate
import com.example.ui.theme.CrimsonLive
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricVolt
import com.example.ui.theme.NeonMint
import com.example.ui.theme.ObsidianInk
import com.example.ui.theme.SteelOutline
import com.example.ui.viewmodel.DataUiState

@Composable
fun CampaignsScreen(
    campaignsState: DataUiState<List<ClippingCampaign>>,
    publicationsState: DataUiState<List<PublishedShort>>,
    onCreateCampaign: (
        name: String,
        creatorHandle: String,
        requiredMention: String,
        hashtagsCsv: String,
        minSec: Long,
        maxSec: Long,
        corner: WatermarkCorner,
        cpmRate: Double
    ) -> Unit,
    onToggleCampaign: (String, Boolean) -> Unit,
    onDeleteCampaign: (String) -> Unit,
    onUpdatePublicationViews: (String, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateForm by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("@Streamer") }
    var mention by remember { mutableStateOf("@Streamer") }
    var hashtags by remember { mutableStateOf("#shorts, #gaming") }
    var minSec by remember { mutableStateOf("15") }
    var maxSec by remember { mutableStateOf("60") }
    var cpm by remember { mutableStateOf("1.50") }

    val publications = (publicationsState as? DataUiState.Success)?.data.orEmpty()
    val totalViews = publications.sumOf { it.viewsCount }
    val totalEarnings = publications.sumOf { it.estimatedEarningsUsd() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Payout Summary Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = BroadcastSlate),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ElectricVolt.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("ESTIMATED CAMPAIGN PAYOUTS", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "$%.2f".format(totalEarnings),
                            style = MaterialTheme.typography.displayLarge,
                            color = ElectricVolt
                        )
                        Text(
                            text = "$totalViews tracked views across ${publications.size} Shorts",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = ElectricVolt.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.Default.AttachMoney,
                            contentDescription = null,
                            tint = ElectricVolt,
                            modifier = Modifier.padding(12.dp).size(30.dp)
                        )
                    }
                }
            }
        }

        // 2. Add Campaign Header & Form
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CAMPAIGN RULES MANAGER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = { showCreateForm = !showCreateForm },
                    modifier = Modifier.testTag("new_campaign_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = ElectricVolt)
                    Spacer(Modifier.width(6.dp))
                    Text(if (showCreateForm) "Close" else "New Campaign", color = ElectricVolt)
                }
            }
        }

        if (showCreateForm) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = BroadcastSlate),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SteelOutline, RoundedCornerShape(18.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Campaign / Brand Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("campaign_name_input")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = handle,
                                onValueChange = { handle = it },
                                label = { Text("Creator Handle") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = mention,
                                onValueChange = { mention = it },
                                label = { Text("Required @Mention") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = hashtags,
                            onValueChange = { hashtags = it },
                            label = { Text("Mandatory Hashtags (comma-separated)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = minSec,
                                onValueChange = { minSec = it },
                                label = { Text("Min (s)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = maxSec,
                                onValueChange = { maxSec = it },
                                label = { Text("Max (s)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = cpm,
                                onValueChange = { cpm = it },
                                label = { Text("CPM ($)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Button(
                            onClick = {
                                if (name.isNotBlank()) {
                                    onCreateCampaign(
                                        name.trim(),
                                        handle.trim().ifBlank { "@Creator" },
                                        mention.trim(),
                                        hashtags,
                                        minSec.toLongOrNull() ?: 15L,
                                        maxSec.toLongOrNull() ?: 60L,
                                        WatermarkCorner.TOP_LEFT,
                                        cpm.toDoubleOrNull() ?: 1.5
                                    )
                                    name = ""
                                    showCreateForm = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricVolt, contentColor = ObsidianInk),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("save_campaign_button")
                        ) {
                            Text("Save Campaign Rules", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        when (campaignsState) {
            is DataUiState.Loading -> {
                item { CircularProgressIndicator(color = ElectricVolt) }
            }
            is DataUiState.Error -> {
                item {
                    Text(campaignsState.message, color = CrimsonLive, style = MaterialTheme.typography.bodyMedium)
                }
            }
            is DataUiState.Success -> {
                if (campaignsState.data.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BroadcastSlate, RoundedCornerShape(16.dp))
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No campaigns configured yet. Tap New Campaign to add Whop / Creator CPM rules.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(campaignsState.data, key = { it.id }) { camp ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BroadcastSlate),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SteelOutline, RoundedCornerShape(16.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(camp.name, style = MaterialTheme.typography.titleMedium)
                                        Surface(color = ElectricVolt.copy(alpha = 0.16f), shape = RoundedCornerShape(8.dp)) {
                                            Text(
                                                text = "$${camp.cpmRateUsd}/1K",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = ElectricVolt,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${camp.requiredMention} • ${camp.minDurationSec}–${camp.maxDurationSec}s • ${camp.requiredHashtags.joinToString(" ")}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = camp.active,
                                    onCheckedChange = { onToggleCampaign(camp.id, it) }
                                )
                                IconButton(onClick = { onDeleteCampaign(camp.id) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete campaign", tint = CrimsonLive)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Posted Shorts Tracker
        item {
            Spacer(Modifier.height(4.dp))
            Text("PUBLISHED SHORTS & VIEW TRACKER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        items(publications, key = { it.id }) { pub ->
            Card(
                colors = CardDefaults.cardColors(containerColor = BroadcastSlate),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SteelOutline, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pub.clipTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${pub.platformEnum().label} • ${pub.viewsCount} views • $%.2f".format(pub.estimatedEarningsUsd()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NeonMint
                        )
                        Text(
                            text = pub.youtubeUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { onUpdatePublicationViews(pub.id, pub.viewsCount + 1000L) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("+1K Views", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                    }
                }
            }
        }
    }
}
