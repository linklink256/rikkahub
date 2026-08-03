package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SubagentManager
import me.rerere.rikkahub.data.files.SubagentMetadata
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage

@Composable
fun ModeInjectionsContent(
    modeInjections: List<PromptInjection.ModeInjection>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(modeInjections) { injection ->
            ListItem(
                headlineContent = {
                    Text(injection.name.ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(injection.id),
                        onCheckedChange = { checked -> onToggle(injection.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun LorebooksContent(
    lorebooks: List<Lorebook>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(lorebooks) { lorebook ->
            ListItem(
                headlineContent = {
                    Text(lorebook.name.ifBlank { stringResource(R.string.extension_content_unnamed_lorebook) })
                },
                supportingContent = if (lorebook.description.isNotBlank()) {
                    {
                        Text(
                            text = lorebook.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(lorebook.id),
                        onCheckedChange = { checked -> onToggle(lorebook.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun SkillsContent(
    skills: List<SkillMetadata>,
    enabledSkills: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(skills, key = { it.skillDir.absolutePath }) { skill ->
            ListItem(
                headlineContent = { Text(skill.name) },
                supportingContent = if (skill.description.isNotBlank()) {
                    {
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = enabledSkills.contains(skill.name),
                        onCheckedChange = { checked -> onToggle(skill.name, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun SubagentsContent(
    subagents: List<SubagentMetadata>,
    enabledSubagents: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
    onToggleGroup: ((group: String, names: Set<String>, checked: Boolean) -> Unit)? = null,
    groupDescriptions: Map<String, String> = emptyMap(),
) {
    // 按 group 分组；default 组始终排在最后；成员名集合在 LazyColumn 外预计算（LazyListScope 非 Composable 上下文）
    val groups = remember(subagents) {
        subagents
            .groupBy { it.group }
            .toList()
            .sortedBy { (group, _) -> if (group == SubagentManager.DEFAULT_GROUP) 1 else 0 }
            .map { (group, members) ->
                Triple(group, members, members.mapTo(LinkedHashSet()) { it.name })
            }
    }
    // 折叠状态：默认全部展开；跨重组/配置变更保留，不持久化
    var collapsedGroups by rememberSaveable { mutableStateOf(setOf<String>()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groups.forEach { (group, members, memberNames) ->
            // "有效启用"口径：空集 = 全部启用，故空集时视为全组已启用
            val selectedCount = if (enabledSubagents.isEmpty()) {
                members.size
            } else {
                members.count { it.name in enabledSubagents }
            }
            val collapsed = group in collapsedGroups
            item(key = "group-header-$group") {
                SubagentGroupHeader(
                    groupName = group,
                    groupDescription = groupDescriptions[group],
                    selectedCount = selectedCount,
                    totalCount = members.size,
                    collapsed = collapsed,
                    onToggleCollapse = {
                        collapsedGroups = if (collapsed) {
                            collapsedGroups - group
                        } else {
                            collapsedGroups + group
                        }
                    },
                    onToggleAll = {
                        onToggleGroup?.invoke(group, memberNames, selectedCount != members.size)
                    },
                )
            }
            item(key = "group-body-$group") {
                AnimatedVisibility(
                    visible = !collapsed,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column {
                        members.forEach { subagent ->
                            ListItem(
                                modifier = Modifier.padding(start = 12.dp),
                                headlineContent = { Text(subagent.name) },
                                supportingContent = if (subagent.description.isNotBlank()) {
                                    {
                                        Text(
                                            text = subagent.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else null,
                                trailingContent = {
                                    Switch(
                                        checked = enabledSubagents.isEmpty() || enabledSubagents.contains(subagent.name),
                                        onCheckedChange = { checked -> onToggle(subagent.name, checked) }
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
private fun SubagentGroupHeader(
    groupName: String,
    selectedCount: Int,
    totalCount: Int,
    onToggleAll: (Boolean) -> Unit,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    groupDescription: String? = null,
) {
    val state = when {
        selectedCount == 0 -> ToggleableState.Off
        selectedCount == totalCount -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val displayName = if (groupName == SubagentManager.DEFAULT_GROUP) {
        stringResource(R.string.subagents_page_default_group)
    } else {
        groupName
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onToggleCollapse() }
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) HugeIcons.ArrowRight01 else HugeIcons.ArrowDown01,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (!groupDescription.isNullOrBlank()) {
                Text(
                    text = groupDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = stringResource(R.string.subagents_page_group_count, selectedCount, totalCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        // TriStateCheckbox 自身消费点击，不触发整头折叠
        TriStateCheckbox(
            state = state,
            onClick = { onToggleAll(selectedCount != totalCount) },
        )
    }
}

@Composable
fun QuickMessagesContent(
    quickMessages: List<QuickMessage>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(quickMessages, key = { it.id }) { quickMessage ->
            ListItem(
                headlineContent = {
                    Text(quickMessage.title.ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                supportingContent = if (quickMessage.content.isNotBlank()) {
                    {
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(quickMessage.id),
                        onCheckedChange = { checked -> onToggle(quickMessage.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun ManageButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Icon(Lucide.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.extension_content_manage),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
fun ExtensionEmptyState(
    message: String,
    buttonText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (buttonText != null && onAction != null) {
            TextButton(onClick = onAction) {
                Icon(HugeIcons.Link01, contentDescription = null)
                Text(buttonText)
            }
        }
    }
}
