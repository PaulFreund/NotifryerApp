package com.notifryer.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.notifryer.wear.data.WearOngoingTopic
import com.notifryer.wear.ui.WearUiState
import com.notifryer.wear.ui.WearViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.Instant

class MainActivity : ComponentActivity() {

    private val viewModel: WearViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    0
                )
            }
        }
        setContent {
            MaterialTheme {
                WearRoute(viewModel)
            }
        }
    }
}

private val wearTimestampFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun WearRoute(viewModel: WearViewModel) {
    val state by viewModel.uiState.collectAsState()
    WearMainScreen(
        state = state,
        onAddAllowTag = viewModel::addAllowTag,
        onRemoveAllowTag = viewModel::removeAllowTag,
        onAddBlockTag = viewModel::addBlockTag,
        onRemoveBlockTag = viewModel::removeBlockTag,
        onToggleShowOngoing = viewModel::updateShowOngoing,
        onToggleMissingAlerts = viewModel::updateMissingAlerts,
        onToggleSuppressSilent = viewModel::updateSuppressSilentWhenConnected,
        onToggleSuppressImportant = viewModel::updateSuppressImportantWhenConnected
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalWearFoundationApi::class)
private fun WearMainScreen(
    state: WearUiState,
    onAddAllowTag: (String) -> Unit,
    onRemoveAllowTag: (String) -> Unit,
    onAddBlockTag: (String) -> Unit,
    onRemoveBlockTag: (String) -> Unit,
    onToggleShowOngoing: (Boolean) -> Unit,
    onToggleMissingAlerts: (Boolean) -> Unit,
    onToggleSuppressSilent: (Boolean) -> Unit,
    onToggleSuppressImportant: (Boolean) -> Unit
) {
    val listState = rememberScalingLazyListState()
    val focusRequester = rememberActiveFocusRequester()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    HierarchicalFocusCoordinator(requiresFocus = { true }) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            state = listState,
            rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(listState)
        ) {
            item {
                Text(text = stringResource(id = R.string.main_title), style = MaterialTheme.typography.title3)
            }
            if (state.ongoingTopics.isEmpty()) {
                item {
                    Text(
                        text = stringResource(id = R.string.main_body_placeholder),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                items(state.ongoingTopics) { topicSnapshot ->
                    OngoingTopicCard(snapshot = topicSnapshot)
                }
            }
            item {
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = state.showOngoingNotifications,
                    onCheckedChange = onToggleShowOngoing,
                    label = { Text(text = stringResource(id = R.string.show_ongoing_title)) },
                    secondaryLabel = { Text(text = stringResource(id = R.string.show_ongoing_description)) },
                    toggleControl = {
                        Icon(
                            imageVector = ToggleChipDefaults.switchIcon(state.showOngoingNotifications),
                            contentDescription = null
                        )
                    }
                )
            }
            item {
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = state.alertOnMissingStatus,
                    onCheckedChange = onToggleMissingAlerts,
                    label = { Text(text = stringResource(id = R.string.show_missing_title)) },
                    secondaryLabel = { Text(text = stringResource(id = R.string.show_missing_description)) },
                    toggleControl = {
                        Icon(
                            imageVector = ToggleChipDefaults.switchIcon(state.alertOnMissingStatus),
                            contentDescription = null
                        )
                    }
                )
            }
            item {
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = state.suppressSilentWhenConnected,
                    onCheckedChange = onToggleSuppressSilent,
                    label = { Text(text = stringResource(id = R.string.suppress_silent_title)) },
                    secondaryLabel = { Text(text = stringResource(id = R.string.suppress_silent_description)) },
                    toggleControl = {
                        Icon(
                            imageVector = ToggleChipDefaults.switchIcon(state.suppressSilentWhenConnected),
                            contentDescription = null
                        )
                    }
                )
            }
            item {
                ToggleChip(
                    modifier = Modifier.fillMaxWidth(),
                    checked = state.suppressImportantWhenConnected,
                    onCheckedChange = onToggleSuppressImportant,
                    label = { Text(text = stringResource(id = R.string.suppress_important_title)) },
                    secondaryLabel = { Text(text = stringResource(id = R.string.suppress_important_description)) },
                    toggleControl = {
                        Icon(
                            imageVector = ToggleChipDefaults.switchIcon(state.suppressImportantWhenConnected),
                            contentDescription = null
                        )
                    }
                )
            }
            item {
                AllowBlockListSection(
                    title = stringResource(id = R.string.allowlist_heading),
                    description = stringResource(id = R.string.allowlist_description),
                    tags = state.allowlist,
                    onAddTag = onAddAllowTag,
                    onRemoveTag = onRemoveAllowTag,
                    allowWildcard = true
                )
            }
            item {
                AllowBlockListSection(
                    title = stringResource(id = R.string.blocklist_heading),
                    description = stringResource(id = R.string.blocklist_description),
                    tags = state.blocklist,
                    onAddTag = onAddBlockTag,
                    onRemoveTag = onRemoveBlockTag,
                    allowWildcard = false
                )
            }
        }
    }
}

@Composable
private fun OngoingTopicCard(snapshot: WearOngoingTopic) {
    val message = snapshot.snapshot.message?.takeIf { it.isNotBlank() }
        ?: stringResource(id = R.string.status_overview_message_placeholder)
    val updatedAt = snapshot.snapshot.updatedAt?.let(Instant::ofEpochMilli)

    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = snapshot.topic, style = MaterialTheme.typography.title2, textAlign = TextAlign.Center)
        message.split('\n').forEach { line ->
            Text(text = line, style = MaterialTheme.typography.body1, textAlign = TextAlign.Center)
        }
        updatedAt?.let {
            Text(
                text = stringResource(id = R.string.status_overview_updated, wearTimestampFormatter.format(it)),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center
            )
        }
    }
}
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AllowBlockListSection(
    title: String,
    description: String,
    tags: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    allowWildcard: Boolean
) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = description, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(6.dp))
        var newTag by rememberSaveable { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newTag,
                onValueChange = { newTag = it },
                label = { Text(stringResource(id = if (allowWildcard) R.string.allowlist_input_label else R.string.blocklist_input_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            val candidate = newTag.trim()
            val exists = tags.any { it.equals(candidate, ignoreCase = true) }
            val canAdd = candidate.isNotEmpty() && !exists && (allowWildcard || candidate != "*")
            Button(onClick = {
                if (canAdd) {
                    onAddTag(candidate)
                    newTag = ""
                }
            }, enabled = canAdd) {
                Text(text = stringResource(id = R.string.add_tag))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
