package com.notifryer.app.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.notifryer.app.R
import com.notifryer.app.data.StatusSnapshot
import com.notifryer.app.ui.SettingsUiState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.Instant

private val payloadTimestampFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())
private val timestampFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    uiState: SettingsUiState,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onAddBlockedTag: (String) -> Unit,
    onRemoveBlockedTag: (String) -> Unit,
    onToggleMissingAlert: (Boolean) -> Unit,
    onDebugInputChange: (String) -> Unit,
    onSendDebugPayload: () -> Unit
) {
    Scaffold { paddingValues ->
        Column(
            modifier = modifier
                .padding(paddingValues)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = stringResource(id = R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(id = R.string.settings_description),
                style = MaterialTheme.typography.bodyMedium
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.status_overview_heading),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (uiState.statuses.isEmpty()) {
                        Text(
                            text = stringResource(id = R.string.status_overview_empty),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            uiState.statuses.forEach { entry ->
                                StatusEntryRow(entry = entry)
                            }
                        }
                    }
                }
            }

            AllowBlockListCard(
                title = stringResource(id = R.string.blocklist_heading),
                description = stringResource(id = R.string.blocklist_description),
                inputLabel = stringResource(id = R.string.blocklist_input_label),
                tags = uiState.blocklist,
                onAddTag = onAddBlockedTag,
                onRemoveTag = onRemoveBlockedTag,
                allowWildcard = false
            )

            AllowBlockListCard(
                title = stringResource(id = R.string.allowlist_heading),
                description = stringResource(id = R.string.allowlist_description),
                inputLabel = stringResource(id = R.string.allowlist_input_label),
                tags = uiState.allowlist,
                onAddTag = onAddTag,
                onRemoveTag = onRemoveTag,
                allowWildcard = true
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.missing_status_alert_heading),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(id = R.string.missing_status_alert_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(id = R.string.missing_status_alert_toggle),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        androidx.compose.material3.Switch(
                            checked = uiState.alertOnMissingStatus,
                            onCheckedChange = onToggleMissingAlert
                        )
                    }
                }
            }

            /* Debug tools card temporarily disabled
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.debug_heading),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(id = R.string.debug_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = uiState.debugPayloadInput,
                        onValueChange = onDebugInputChange,
                        label = { Text(stringResource(id = R.string.debug_input_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 12
                    )
                    uiState.debugError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(onClick = onSendDebugPayload) {
                        Icon(imageVector = Icons.Default.BugReport, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(id = R.string.debug_send))
                    }
                    Text(
                        text = stringResource(id = R.string.debug_last_payload_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    val lastPayload = uiState.lastPayload
                    if (lastPayload == null) {
                        Text(
                            text = stringResource(id = R.string.debug_last_payload_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        Text(
                            text = payloadTimestampFormatter.format(lastPayload.receivedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = lastPayload.payload,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            */
        }
    }
}

@Composable
private fun StatusEntryRow(entry: StatusSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "• ${entry.topic}",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = entry.message?.takeIf { it.isNotBlank() }
                ?: stringResource(id = R.string.status_overview_message_placeholder),
            style = MaterialTheme.typography.bodyMedium
        )
        val instant = entry.updatedAt ?: entry.sequence?.let(Instant::ofEpochMilli)
        instant?.let {
            Text(
                text = stringResource(id = R.string.status_overview_updated, timestampFormatter.format(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllowBlockListCard(
    title: String,
    description: String,
    inputLabel: String,
    tags: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    allowWildcard: Boolean
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            var newTag by rememberSaveable { mutableStateOf("") }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = { Text(inputLabel) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                val candidate = newTag.trim()
                val exists = tags.any { it.equals(candidate, ignoreCase = true) }
                val canAdd = candidate.isNotEmpty() && !exists && (allowWildcard || candidate != "*")
                Button(
                    onClick = {
                        if (canAdd) {
                            onAddTag(candidate)
                            newTag = ""
                        }
                    },
                    enabled = canAdd
                ) {
                    Text(text = stringResource(id = R.string.add_tag))
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { tag ->
                    val isWildcard = tag == "*"
                    FilterChip(
                        selected = false,
                        onClick = { if (!isWildcard) onRemoveTag(tag) },
                        enabled = !isWildcard || !allowWildcard,
                        label = { Text(tag) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Close, contentDescription = null)
                        }
                    )
                }
            }
        }
    }
}
