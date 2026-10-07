package me.chosante.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemEquipCriterion
import me.chosante.common.ItemType
import me.chosante.common.Monster
import me.chosante.common.Rarity
import me.chosante.common.RuneColor
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationRarity
import me.chosante.common.history.HistoryEntry
import me.chosante.ui.history.normalizeTags
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.localizedCollator
import me.chosante.ui.i18n.sortedByLocalized
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.Modal
import me.chosante.ui.state.PickerMode
import me.chosante.ui.state.color
import me.chosante.ui.state.freeBuildName
import me.chosante.ui.state.statCatalog
import me.chosante.ui.state.tagInputSuggestions
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WRarityColor
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

@Composable
fun ModalHost(
    modal: Modal?,
    excludedCharacteristics: Set<Characteristic>,
    equipmentCatalog: List<Equipment>?,
    forcedItemNames: Set<String> = emptySet(),
    excludedItemNames: Set<String> = emptySet(),
    level: Int = 245,
    minLevel: Int = 0,
    maxRarity: Rarity = Rarity.EPIC,
    excludedRarities: Set<Rarity> = emptySet(),
    forcedSublimations: List<String> = emptyList(),
    excludedSublimations: List<String> = emptyList(),
    forcedPassives: List<String> = emptyList(),
    onSelectStat: (Characteristic) -> Unit,
    onPickItem: (Equipment) -> Unit,
    onPickSublimation: (Sublimation) -> Unit = {},
    onPickPassive: (me.chosante.common.Passive) -> Unit = {},
    passiveClass: me.chosante.common.CharacterClass = me.chosante.common.CharacterClass.CRA,
    onPickBoss: (Monster) -> Unit = {},
    selectedBoss: Monster? = null,
    selectedCharacteristics: Set<Characteristic> = excludedCharacteristics,
    hideChosen: Boolean = false,
    onHideChosenChange: (Boolean) -> Unit = {},
    onRemoveStat: (Characteristic) -> Unit = {},
    onRemoveForcedItem: (String) -> Unit = {},
    onRemoveExcludedItem: (String) -> Unit = {},
    onRemoveForcedSublimation: (String) -> Unit = {},
    onRemoveExcludedSublimation: (String) -> Unit = {},
    onRemovePassive: (String) -> Unit = {},
    runePickerCarrier: Equipment? = null,
    runeOptions: List<RuneType> = emptyList(),
    initialPinnedRunes: List<Int> = emptyList(),
    onConfirmItemRunes: (itemName: String, runeIds: List<Int>) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    suggestedSaveName: String = "",
    isEditingExisting: Boolean = false,
    takenNames: Set<String> = emptySet(),
    takenNamesForNew: Set<String> = takenNames + if (isEditingExisting) setOf(suggestedSaveName.trim().lowercase()) else emptySet(),
    editingEntry: HistoryEntry? = null,
    existingFolders: List<String> = emptyList(),
    existingTags: List<String> = emptyList(),
    onSaveBuild: (name: String, note: String?, asNew: Boolean) -> Unit = { _, _, _ -> },
    onEditBuild: (id: String, name: String, note: String?, tags: List<String>, folder: String?) -> Unit = { _, _, _, _, _ -> },
    onDeleteBuild: (id: String) -> Unit = {},
    onRenameFolder: (oldName: String, newName: String) -> Unit = { _, _ -> },
    onDeleteFolder: (name: String) -> Unit = {},
    onCreateTag: (name: String) -> Unit = {},
    onRenameTag: (oldName: String, newName: String) -> Unit = { _, _ -> },
    onDeleteTag: (name: String) -> Unit = {},
    onConfirmReSearch: () -> Unit = {},
    onImportBuild: (json: String) -> Unit = {},
    validateImport: (json: String) -> Boolean = { false },
    onClipboardText: () -> String = { "" },
) {
    if (modal == null) return
    Scrim(onDismiss = onDismiss) {
        when (modal) {
            Modal.AddStat ->
                AddStatModal(
                    excluded = excludedCharacteristics - selectedCharacteristics,
                    selected = selectedCharacteristics,
                    hideChosen = hideChosen,
                    onHideChosenChange = onHideChosenChange,
                    onRemove = onRemoveStat,
                    onSelect = onSelectStat,
                    onDone = onDismiss
                )

            is Modal.ItemPicker ->
                ItemPickerModal(
                    mode = modal.mode,
                    equipmentCatalog = equipmentCatalog,
                    forcedNames = forcedItemNames,
                    excludedNames = excludedItemNames,
                    hideChosen = hideChosen,
                    onHideChosenChange = onHideChosenChange,
                    onRemoveForced = onRemoveForcedItem,
                    onRemoveExcluded = onRemoveExcludedItem,
                    level = level,
                    minLevel = minLevel,
                    maxRarity = maxRarity,
                    excludedRarities = excludedRarities,
                    onPick = onPickItem,
                    onDone = onDismiss
                )

            is Modal.SublimationPicker ->
                SublimationPickerModal(
                    exclude = modal.exclude,
                    selectedNames = if (modal.exclude) excludedSublimations else forcedSublimations,
                    hideChosen = hideChosen,
                    onHideChosenChange = onHideChosenChange,
                    onRemove = if (modal.exclude) onRemoveExcludedSublimation else onRemoveForcedSublimation,
                    onPick = onPickSublimation,
                    onDone = onDismiss
                )

            Modal.PassivePicker ->
                PassivePickerModal(
                    clazz = passiveClass,
                    level = level,
                    selectedNames = forcedPassives,
                    hideChosen = hideChosen,
                    onHideChosenChange = onHideChosenChange,
                    onRemove = onRemovePassive,
                    onPick = onPickPassive,
                    onDone = onDismiss
                )

            Modal.BossPicker ->
                BossPickerModal(selectedBoss = selectedBoss, onPick = onPickBoss)

            is Modal.ItemRunePicker ->
                // Resolve the carrier at render time from the current build; if it's gone (e.g. a new
                // search replaced it), close from a side-effect rather than writing state in composition.
                if (runePickerCarrier == null) {
                    LaunchedEffect(modal.itemName) { onDismiss() }
                } else {
                    key(runePickerCarrier.equipmentId) {
                        ItemRunePickerModal(
                            carrier = runePickerCarrier,
                            runeOptions = runeOptions,
                            initialSelection = initialPinnedRunes,
                            onConfirm = { ids -> onConfirmItemRunes(modal.itemName, ids) },
                            onCancel = onDismiss
                        )
                    }
                }

            Modal.SaveBuild ->
                SaveBuildModal(
                    initialName = suggestedSaveName,
                    isEditingExisting = isEditingExisting,
                    takenNames = takenNames,
                    takenNamesForNew = takenNamesForNew,
                    onSave = onSaveBuild,
                    onCancel = onDismiss
                )

            Modal.ImportBuild ->
                ImportBuildModal(
                    validate = validateImport,
                    clipboardText = onClipboardText,
                    onImport = onImportBuild,
                    onCancel = onDismiss
                )

            is Modal.EditBuild -> {
                // Resolve at render time so the dialog never edits a stale snapshot. If the entry
                // vanished (e.g. deleted elsewhere), close from a side-effect (never write state
                // during composition).
                if (editingEntry == null || editingEntry.id != modal.id) {
                    LaunchedEffect(modal.id) { onDismiss() }
                } else {
                    // key on the entry id so the form's internal state resets if the dialog is ever
                    // re-pointed at a different build without closing in between.
                    key(editingEntry.id) {
                        EditBuildModal(
                            entry = editingEntry,
                            takenNames = takenNames,
                            existingFolders = existingFolders,
                            existingTags = existingTags,
                            onSave = onEditBuild,
                            onCancel = onDismiss
                        )
                    }
                }
            }

            is Modal.RenameFolder ->
                RenameValueModal(
                    title = tr(Tr.RENAME_FOLDER_TITLE),
                    label = tr(Tr.FOLDER_LABEL),
                    initialName = modal.name,
                    onRename = { newName -> onRenameFolder(modal.name, newName) },
                    onCancel = onDismiss
                )

            is Modal.ConfirmDeleteFolder ->
                ConfirmModal(
                    title = tr(Tr.DELETE_FOLDER_TITLE),
                    emphasis = modal.name,
                    message = tr(Tr.DELETE_FOLDER_HINT),
                    confirmLabel = tr(Tr.ACTION_DELETE),
                    confirmColor = WColor.danger,
                    onConfirm = { onDeleteFolder(modal.name) },
                    onCancel = onDismiss
                )

            Modal.CreateTag ->
                RenameValueModal(
                    title = tr(Tr.CREATE_TAG_TITLE),
                    label = tr(Tr.TAGS_LABEL),
                    initialName = "",
                    onRename = onCreateTag,
                    onCancel = onDismiss
                )

            is Modal.RenameTag ->
                RenameValueModal(
                    title = tr(Tr.RENAME_TAG_TITLE),
                    label = tr(Tr.TAGS_LABEL),
                    initialName = modal.name,
                    onRename = { newName -> onRenameTag(modal.name, newName) },
                    onCancel = onDismiss
                )

            is Modal.ConfirmDeleteTag ->
                ConfirmModal(
                    title = tr(Tr.DELETE_TAG_TITLE),
                    emphasis = modal.name,
                    message = tr(Tr.DELETE_TAG_HINT),
                    confirmLabel = tr(Tr.ACTION_DELETE),
                    confirmColor = WColor.danger,
                    onConfirm = { onDeleteTag(modal.name) },
                    onCancel = onDismiss
                )

            is Modal.ConfirmDelete ->
                ConfirmModal(
                    title = tr(Tr.DELETE_TITLE),
                    emphasis = modal.name,
                    message = tr(Tr.DELETE_HINT),
                    confirmLabel = tr(Tr.ACTION_DELETE),
                    confirmColor = WColor.danger,
                    onConfirm = { onDeleteBuild(modal.id) },
                    onCancel = onDismiss
                )

            Modal.ConfirmReSearch ->
                ConfirmModal(
                    title = tr(Tr.RESEARCH_TITLE),
                    emphasis = null,
                    message = tr(Tr.RESEARCH_HINT),
                    confirmLabel = tr(Tr.RESEARCH_CONFIRM),
                    confirmColor = WColor.accent,
                    onConfirm = onConfirmReSearch,
                    onCancel = onDismiss
                )
        }
    }
}

@Composable
internal fun Scrim(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // The card holds the keyboard focus from the start, so Esc works in every modal — a confirm dialog with no text field
    // included. A field that asks for the focus after it (SearchField's autoFocus) just takes it over: it is inside the card.
    val cardFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cardFocus.requestFocus() }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xCC0B0C0F))
                // Esc closes whichever modal is open. A preview handler, so it fires wherever the focus is inside the card.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                }.noRippleClickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        // Card swallows its own clicks so it does not dismiss the scrim.
        Box(modifier = Modifier.focusRequester(cardFocus).noRippleClickable {}) {
            content()
        }
    }
}

private data class PickerChoice<K>(
    val key: K,
    val label: String,
    val badge: String? = null,
)

private data class PickerSelection<K>(
    val choices: List<PickerChoice<K>>,
    val hideChosen: Boolean,
    val onHideChosenChange: (Boolean) -> Unit,
    val onRemove: (K) -> Unit,
    val toggledKeys: Set<K> = choices.map { it.key }.toSet(),
)

/** Selection, toggling and completion are shared; domain callbacks retain validation and persistence. */
@Composable
private fun <T, K> PickerScaffold(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    entries: List<T>,
    entryKey: (T) -> K,
    emptyText: String,
    rowKey: (T) -> Any = { entryKey(it) as Any },
    selection: PickerSelection<K>? = null,
    onPick: ((T) -> Unit)? = null,
    selectedKey: K? = null,
    scrollToKey: K? = null,
    canPick: Boolean = true,
    showMatchCount: Boolean = false,
    listHeight: androidx.compose.ui.unit.Dp = 440.dp,
    loading: Boolean = false,
    header: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    onDone: (() -> Unit)? = null,
    listContent: (@Composable (List<T>, @Composable (T, Modifier) -> Unit) -> Unit)? = null,
    row: @Composable (T) -> Unit = {},
) {
    val choices = selection?.choices.orEmpty().associateBy { it.key }
    val visible = entries.filterNot { selection?.hideChosen == true && entryKey(it) in choices }
    // rememberLazyListState consumes this index only when the picker opens. Search edits never
    // jump back to the current value; the list keeps its normal scroll behavior afterward.
    val initialIndex = if (query.isBlank() && scrollToKey != null) entries.indexOfFirst { entryKey(it) == scrollToKey }.coerceAtLeast(0) else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val renderRow: @Composable (T, Modifier) -> Unit = { entry, modifier ->
        if (selection == null && onPick == null) {
            Box(modifier) { row(entry) }
        } else {
            val identity = entryKey(entry)
            val chosen = choices[identity]
            val removes = selection?.toggledKeys?.contains(identity) == true
            PickerEntry(
                selected = chosen != null || identity == selectedKey,
                enabled = canPick || removes,
                badge = chosen?.badge,
                role = if (selection == null) Role.RadioButton else Role.Checkbox,
                modifier = modifier.testTag("picker-choice-${rowKey(entry)}"),
                onClick = { if (removes) selection.onRemove(identity) else onPick?.invoke(entry) }
            ) { row(entry) }
        }
    }
    ModalCard(title = title) {
        if (loading) {
            LoadingState(message = tr(Tr.LOADING_ITEMS))
            return@ModalCard
        }
        if (selection != null) {
            PickerChosenStrip(selection)
            PickerToggle(checked = selection.hideChosen, label = tr(Tr.PICKER_HIDE_CHOSEN), onToggle = { selection.onHideChosenChange(!selection.hideChosen) })
            Spacer(modifier = Modifier.height(WDimens.gap))
        }
        header()
        SearchField(query = query, onQueryChange = onQueryChange, placeholder = placeholder, autoFocus = true)
        Spacer(modifier = Modifier.height(WDimens.gap))
        if (showMatchCount) PickerMatchCount(visible.size)
        if (listContent != null) {
            listContent(visible, renderRow)
        } else {
            val maxListHeight = if (selection == null) listHeight else minOf(listHeight, if (choices.isEmpty()) 340.dp else 260.dp)
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = maxListHeight),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visible, key = rowKey) { renderRow(it, Modifier) }
            }
        }
        if (visible.isEmpty()) {
            Text(text = emptyText, style = WTypography.bodyMedium.copy(color = WColor.muted), modifier = Modifier.padding(vertical = 16.dp))
        }
        if (onDone != null) PickerDoneButton(onDone = onDone)
        footer()
    }
}

@Composable
private fun PickerEntry(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    role: Role = Role.Checkbox,
    content: @Composable () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(if (selected) WColor.accent.copy(alpha = 0.12f) else WColor.raised)
                .border(1.dp, if (selected) WColor.accent else WColor.border, RoundedCornerShape(9.dp))
                .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
                .alpha(if (enabled) 1f else 0.45f)
                .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(if (selected) "✓" else "□", style = WTypography.labelMedium.copy(color = if (selected) WColor.accent else WColor.muted))
        Column(Modifier.weight(1f)) {
            content()
            if (badge != null) Text(badge, style = WTypography.labelSmall.copy(color = WColor.accent), modifier = Modifier.padding(start = 11.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <K> PickerChosenStrip(selection: PickerSelection<K>) {
    if (selection.choices.isEmpty()) return
    val lang = LocalLang.current
    Text(tr(Tr.PICKER_CHOSEN_COUNT).format(selection.choices.size), style = WTypography.labelMedium)
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 96.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        selection.choices.forEach { choice ->
            Row(
                modifier =
                    Modifier
                        .clip(
                            RoundedCornerShape(7.dp)
                        ).background(WColor.raised)
                        .border(1.dp, WColor.accent.copy(alpha = 0.4f), RoundedCornerShape(7.dp))
                        .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    choice.label + (
                        choice.badge?.let {
                            " · $it"
                        } ?: ""
                    ),
                    style = WTypography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 290.dp)
                )
                Text(
                    "✕",
                    style = WTypography.labelMedium,
                    modifier =
                        Modifier
                            .testTag("picker-remove-${choice.key}")
                            .semantics { contentDescription = Tr.PICKER_REMOVE_CHOSEN.value(lang).format(choice.label) }
                            .clickable { selection.onRemove(choice.key) }
                            .padding(8.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun AddStatModal(
    excluded: Set<Characteristic>,
    selected: Set<Characteristic>,
    hideChosen: Boolean,
    onHideChosenChange: (Boolean) -> Unit,
    onRemove: (Characteristic) -> Unit,
    onSelect: (Characteristic) -> Unit,
    onDone: () -> Unit,
) {
    val lang = LocalLang.current
    var query by remember { mutableStateOf("") }
    val results =
        remember(query, lang, excluded) {
            statCatalog.filter { it.characteristic !in excluded && it.label(lang).contains(query.trim(), ignoreCase = true) }
        }
    PickerScaffold(
        title = tr(Tr.ADD_TARGET_STAT_TITLE),
        query = query,
        onQueryChange = { query = it },
        placeholder = tr(Tr.FILTER_STATS),
        entries = results,
        entryKey = { it.characteristic },
        selection =
            PickerSelection(
                statCatalog.filter { it.characteristic in selected }.map { PickerChoice(it.characteristic, it.label(lang)) },
                hideChosen,
                onHideChosenChange,
                onRemove
            ),
        onPick = { onSelect(it.characteristic) },
        emptyText = tr(Tr.NO_MATCHING_STAT),
        onDone = onDone,
        listContent = { visible, renderTile ->
            val sections =
                statSections.mapNotNull { section ->
                    val stats = visible.filter { section.accepts(it.characteristic) }.sortedByLocalized(lang) { it.label(lang) }
                    if (stats.isEmpty()) null else section to stats
                }
            Column(
                modifier =
                    Modifier
                        .heightIn(max = if (selected.isEmpty()) 340.dp else 260.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                sections.forEachIndexed { sectionIndex, (section, stats) ->
                    if (sectionIndex > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = tr(section.title),
                        style = WTypography.labelMedium.copy(color = WColor.muted)
                    )
                    stats.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            pair.forEach { def ->
                                renderTile(def, Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        row = { def -> CatalogTile(characteristic = def.characteristic, glyph = def.glyph, color = def.color, label = def.label(lang)) }
    )
}

private data class StatSection(
    val title: Tr,
    val accepts: (Characteristic) -> Boolean,
)

private val coreStats =
    setOf(
        Characteristic.ACTION_POINT,
        Characteristic.MOVEMENT_POINT,
        Characteristic.RANGE,
        Characteristic.WAKFU_POINT,
        Characteristic.CRITICAL_HIT,
        Characteristic.HP
    )

private val elementalMasteryStats =
    setOf(
        Characteristic.MASTERY_ELEMENTARY,
        Characteristic.MASTERY_ELEMENTARY_WATER,
        Characteristic.MASTERY_ELEMENTARY_FIRE,
        Characteristic.MASTERY_ELEMENTARY_EARTH,
        Characteristic.MASTERY_ELEMENTARY_WIND
    )

private val specializedMasteryStats =
    setOf(
        Characteristic.MASTERY_DISTANCE,
        Characteristic.MASTERY_MELEE,
        Characteristic.MASTERY_CRITICAL,
        Characteristic.MASTERY_BACK,
        Characteristic.MASTERY_BERSERK,
        Characteristic.MASTERY_HEALING
    )

private val masteryStats = elementalMasteryStats + specializedMasteryStats

private val statSections =
    listOf(
        StatSection(Tr.STAT_GROUP_CORE) { it in coreStats },
        StatSection(Tr.MASTERY_ELEMENTALS) { it in elementalMasteryStats },
        StatSection(Tr.MASTERY_SPECIALIZED) { it in specializedMasteryStats },
        StatSection(Tr.STAT_GROUP_RESISTANCES) { it.name.startsWith("RESISTANCE") },
        StatSection(Tr.STAT_GROUP_SECONDARY) {
            it !in coreStats &&
                it !in masteryStats &&
                !it.name.startsWith("RESISTANCE")
        }
    )

@Composable
private fun CatalogTile(
    characteristic: Characteristic,
    glyph: String,
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .height(44.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    // Light tile so dark line-art stat symbols stay legible on the dark theme (see WColor.iconTile).
                    .background(WColor.iconTile)
                    .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            StatGlyphIcon(characteristic = characteristic, glyph = glyph, color = color, iconSize = 19.dp)
        }
        Text(
            text = label,
            style = WTypography.bodyMedium.copy(color = WColor.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ItemPickerModal(
    mode: PickerMode,
    equipmentCatalog: List<Equipment>?,
    forcedNames: Set<String>,
    excludedNames: Set<String>,
    hideChosen: Boolean,
    onHideChosenChange: (Boolean) -> Unit,
    onRemoveForced: (String) -> Unit,
    onRemoveExcluded: (String) -> Unit,
    level: Int,
    minLevel: Int,
    maxRarity: Rarity,
    excludedRarities: Set<Rarity>,
    onPick: (Equipment) -> Unit,
    onDone: () -> Unit,
) {
    val lang = LocalLang.current
    var query by remember { mutableStateOf("") }
    val selectedNames = forcedNames + excludedNames
    var equippableOnly by remember { mutableStateOf(true) }
    var slotFilter by remember { mutableStateOf<ItemType?>(null) }
    val results =
        remember(query, equipmentCatalog, selectedNames, level, minLevel, maxRarity, excludedRarities, equippableOnly, slotFilter, lang) {
            val catalog = equipmentCatalog ?: return@remember emptyList()
            val q = query.trim()
            catalog
                .asSequence()
                .filter { slotFilter == null || it.itemType == slotFilter }
                .filter { !equippableOnly || it.isEquippableForPicker(level, minLevel, maxRarity, excludedRarities) }
                .filter { equipment ->
                    q.isBlank() ||
                        equipment.name.fr.contains(q, ignoreCase = true) ||
                        equipment.name.en.contains(q, ignoreCase = true) ||
                        equipment.name.es.contains(q, ignoreCase = true) ||
                        equipment.name.pt.contains(q, ignoreCase = true)
                }.toList()
                .sortedByLocalized(lang) { it.localizedName(lang) }
        }
    val catalogById = remember(equipmentCatalog) { equipmentCatalog.orEmpty().associateBy { it.equipmentId } }
    val title = if (mode == PickerMode.Forced) tr(Tr.REQUIRE_ITEM_TITLE) else tr(Tr.BAN_ITEM_TITLE)
    PickerScaffold(
        title = title,
        query = query,
        onQueryChange = { query = it },
        placeholder = tr(Tr.SEARCH_ITEMS),
        entries = results,
        entryKey = { it.name.fr },
        selection =
            PickerSelection(
                selectedNames.map { name ->
                    PickerChoice(
                        name,
                        equipmentCatalog.orEmpty().firstOrNull { it.name.fr == name }?.localizedName(lang) ?: name,
                        tr(if (name in forcedNames) Tr.PICKER_FORCED else Tr.PICKER_EXCLUDED)
                    )
                },
                hideChosen,
                onHideChosenChange,
                { name -> if (name in forcedNames) onRemoveForced(name) else onRemoveExcluded(name) },
                toggledKeys = if (mode == PickerMode.Forced) forcedNames else excludedNames
            ),
        onPick = onPick,
        rowKey = { it.equipmentId },
        emptyText = tr(Tr.NO_MATCHING_ITEM),
        showMatchCount = true,
        loading = equipmentCatalog == null,
        header = {
            PickerToggle(checked = equippableOnly, label = tr(Tr.EQUIPPABLE_ONLY), onToggle = { equippableOnly = !equippableOnly })
            Spacer(modifier = Modifier.height(WDimens.gap))
            ItemSlotFilter(selected = slotFilter, onSelect = { slotFilter = it })
            Spacer(modifier = Modifier.height(WDimens.gap))
        },
        onDone = onDone,
        row = { equipment ->
            ItemResultRow(equipment = equipment, catalog = catalogById)
        }
    )
}

@Composable
private fun PickerMatchCount(count: Int) {
    Text(
        text = tr(Tr.PICKER_MATCH_COUNT).format(count),
        style = WTypography.labelSmall.copy(color = WColor.muted),
        modifier = Modifier.padding(bottom = WDimens.gap)
    )
}

@Composable
private fun ItemSlotFilter(
    selected: ItemType?,
    onSelect: (ItemType?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PickerFilterChip(label = tr(Tr.ALL_SLOTS), selected = selected == null, color = WColor.accent, onClick = { onSelect(null) })
        ItemType.entries.forEach { slot ->
            PickerFilterChip(
                label = slot.label(LocalLang.current),
                selected = selected == slot,
                color = WColor.accent,
                onClick = { onSelect(slot) },
                iconPath = "assets/itemTypes/${slot.id}.png"
            )
        }
    }
}

private fun Equipment.isEquippableForPicker(
    level: Int,
    minLevel: Int,
    maxRarity: Rarity,
    excludedRarities: Set<Rarity>,
): Boolean {
    val levelOk = itemType == ItemType.PETS || itemType == ItemType.MOUNTS || this.level in minLevel..level
    val rarityOk = rarity <= maxRarity && rarity !in excludedRarities
    return levelOk && rarityOk
}

private fun Equipment.localizedName(lang: Lang): String = name.localized(lang)

@Composable
private fun LoadingState(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            color = WColor.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp)
        )
        Text(text = message, style = WTypography.bodyMedium.copy(color = WColor.muted))
    }
}

/** A compact lazy item row with the game's equip conditions below its name. */
@Composable
private fun ItemResultRow(
    equipment: Equipment,
    catalog: Map<Int, Equipment>,
) {
    val lang = LocalLang.current
    val conditions =
        remember(equipment, catalog, lang) {
            formatItemEquipConditions(equipment.equipCriterion ?: ItemEquipCriterion(equipment.equipmentId, raw = ""), catalog, lang)
        }
    ItemConditionsHover(conditions) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(WColor.raised)
                    .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ItemThumbnail(equipment = equipment, size = 38.dp)
            Column(modifier = Modifier.weight(1f)) {
                val name = equipment.name.localized(lang)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RarityIcon(rarity = equipment.rarity, size = 14.dp)
                    Text(
                        text = name,
                        style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ItemConditionLines(conditions, compact = true)
                Text(
                    text = "${tr(Tr.LEVEL_PREFIX_SHORT)} ${equipment.level} · ${equipment.itemType.label(lang)} · ${equipment.rarity.label(lang)}",
                    style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SublimationPickerModal(
    exclude: Boolean = false,
    selectedNames: List<String>,
    hideChosen: Boolean,
    onHideChosenChange: (Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onPick: (Sublimation) -> Unit,
    onDone: () -> Unit,
) {
    val lang = LocalLang.current
    val results =
        remember(lang) {
            WakfuBestBuildFinderAlgorithm.sublimations.distinctBy { it.stateId }
        }
    var query by remember { mutableStateOf("") }
    var rarityFilter by remember { mutableStateOf<SublimationRarity?>(null) }
    val filtered =
        remember(query, results, selectedNames, rarityFilter, lang) {
            val q = query.trim()
            results
                .asSequence()
                .filter { rarityFilter == null || it.rarity == rarityFilter }
                .filter { sub ->
                    q.isBlank() ||
                        sub.name.fr.contains(q, ignoreCase = true) ||
                        sub.name.en.contains(q, ignoreCase = true) ||
                        sub.name.es.contains(q, ignoreCase = true) ||
                        sub.name.pt.contains(q, ignoreCase = true) ||
                        sublimationEffectText(sub, lang).contains(q, ignoreCase = true)
                }.toList()
                .sortedWith(
                    compareBy<Sublimation> { it.rarity.sortOrder() }
                        .thenComparator { left, right ->
                            localizedCollator(lang).compare(left.name.localized(lang), right.name.localized(lang))
                        }
                )
        }
    PickerScaffold(
        title = tr(if (exclude) Tr.EXCLUDE_SUBLIMATION_TITLE else Tr.REQUIRE_SUBLIMATION_TITLE),
        query = query,
        onQueryChange = { query = it },
        placeholder = tr(Tr.SEARCH_SUBLIMATIONS),
        entries = filtered,
        entryKey = { it.name.fr },
        emptyText = tr(Tr.NO_MATCHING_SUBLIMATION),
        selection =
            PickerSelection(
                selectedNames.distinct().map { name ->
                    PickerChoice(name, results.firstOrNull { it.name.fr == name }?.name?.localized(lang) ?: name)
                },
                hideChosen,
                onHideChosenChange,
                onRemove
            ),
        onPick = onPick,
        rowKey = { it.stateId },
        showMatchCount = true,
        onDone = onDone,
        header = {
            SublimationRarityFilter(selected = rarityFilter, onSelect = { rarityFilter = it })
            Spacer(modifier = Modifier.height(WDimens.gap))
        },
        row = { entry -> SublimationResultRow(sub = entry, lang = lang) }
    )
}

@Composable
private fun SublimationResultRow(
    sub: Sublimation,
    lang: Lang,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = sub.name.localized(lang),
                style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = sub.rarity.label(lang),
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = sub.rarity.displayColor())
            )
            Text(
                // The GENERATION tier (the name's I/II/III) — matches how the level cap filters, so a
                // "Tier 3" sub visibly matches the "≤ 3" cap (the shard upgrade level maxTier is internal).
                text = tr(Tr.SUBLIMATION_TIER_SHORT).format(sub.nameTier),
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
            )
            SublimationStackBadge(sub)
        }
        sublimationEffectText(sub, lang).takeIf { it.isNotBlank() }?.let { effect ->
            Text(
                text = effect,
                style = WTypography.labelSmall.copy(color = WColor.muted),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SublimationRarityFilter(
    selected: SublimationRarity?,
    onSelect: (SublimationRarity?) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        PickerFilterChip(label = tr(Tr.RARITY_ALL), selected = selected == null, color = WColor.accent, onClick = { onSelect(null) })
        listOf(SublimationRarity.NORMAL, SublimationRarity.EPIC, SublimationRarity.RELIC).forEach { rarity ->
            PickerFilterChip(
                label = rarity.label(LocalLang.current),
                selected = selected == rarity,
                color = rarity.displayColor(),
                onClick = { onSelect(rarity) }
            )
        }
    }
}

@Composable
private fun PickerFilterChip(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
    iconPath: String? = null,
) {
    Box(
        modifier =
            Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) color.copy(alpha = 0.18f) else WColor.bg)
                .border(1.dp, if (selected) color.copy(alpha = 0.55f) else WColor.border, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            iconPath?.let { path ->
                rememberClasspathBitmap(path)?.let { bitmap ->
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = label,
                style = WTypography.labelSmall.copy(color = if (selected) color else WColor.muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            )
        }
    }
}

private fun SublimationRarity.sortOrder(): Int =
    when (this) {
        SublimationRarity.EPIC -> 0
        SublimationRarity.RELIC -> 1
        SublimationRarity.NORMAL -> 2
    }

private fun SublimationRarity.displayColor(): Color =
    when (this) {
        SublimationRarity.EPIC -> WRarityColor.epic
        SublimationRarity.RELIC -> WRarityColor.relic
        SublimationRarity.NORMAL -> WColor.success
    }

@Composable
private fun PassivePickerModal(
    clazz: me.chosante.common.CharacterClass,
    level: Int,
    selectedNames: List<String>,
    hideChosen: Boolean,
    onHideChosenChange: (Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onPick: (me.chosante.common.Passive) -> Unit,
    onDone: () -> Unit,
) {
    val lang = LocalLang.current
    val all = remember(clazz) { PassiveCatalog.forClass(clazz) }
    var query by remember { mutableStateOf("") }
    val filtered =
        remember(query, all, selectedNames, lang) {
            val q = query.trim()
            all
                .asSequence()
                .filter { passive ->
                    q.isBlank() ||
                        passive.name?.localized(lang)?.contains(q, ignoreCase = true) == true ||
                        passive.description?.localized(lang)?.contains(q, ignoreCase = true) == true
                }.toList()
                .sortedByLocalized(lang) { it.name?.localized(lang).orEmpty() }
        }
    PickerScaffold(
        title = tr(Tr.REQUIRE_PASSIVE_TITLE),
        query = query,
        onQueryChange = { query = it },
        placeholder = tr(Tr.SEARCH_PASSIVES),
        entries = filtered,
        entryKey = { it.name?.fr ?: it.spellId.toString() },
        emptyText = tr(Tr.NO_MATCHING_PASSIVE),
        selection =
            PickerSelection(
                selectedNames.distinct().map { name ->
                    PickerChoice(name, all.firstOrNull { it.name?.fr == name }?.name?.localized(lang) ?: name)
                },
                hideChosen,
                onHideChosenChange,
                onRemove
            ),
        onPick = onPick,
        canPick = selectedNames.size < PassiveCatalog.slotsForLevel(level),
        header = {
            Text("${selectedNames.size} / ${PassiveCatalog.slotsForLevel(level)}", style = WTypography.labelMedium)
            if (selectedNames.size >= PassiveCatalog.slotsForLevel(level)) Text(tr(Tr.PASSIVE_SLOTS_FULL), style = WTypography.labelSmall.copy(color = WColor.muted))
            Spacer(Modifier.height(WDimens.gap))
        },
        rowKey = { it.spellId },
        showMatchCount = true,
        onDone = onDone,
        row = { entry -> PassiveResultRow(passive = entry) }
    )
}

@Composable
private fun PassiveResultRow(passive: me.chosante.common.Passive) {
    val lang = LocalLang.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        PassiveIcon(gfxId = passive.gfxId, size = 28.dp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Text(
                text = passive.name?.localized(lang) ?: passive.spellId.toString(),
                style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            passive.description?.localized(lang)?.takeIf { it.isNotBlank() }?.let { effect ->
                Text(
                    text = effect,
                    style = WTypography.labelSmall.copy(color = WColor.muted),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BossPickerModal(
    selectedBoss: Monster?,
    onPick: (Monster) -> Unit,
) {
    val lang = LocalLang.current
    // Boss mode targets bosses, not every creature in the bestiary: the roster keeps only boss-tier entries (rank ≥ 1, ~225 of
    // the ~2 850 monsters), named and sorted in the language of the app. It is short enough to list in full, so there is no
    // cap — the old take(120) silently hid every boss past "M".
    val results = remember(lang) { bossRoster(WakfuBestBuildFinderAlgorithm.monsters, lang) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, results) { results.filter { it.matchesQuery(query) } }
    PickerScaffold(
        title = tr(Tr.CHOOSE_BOSS_TITLE),
        query = query,
        onQueryChange = { query = it },
        placeholder = tr(Tr.SEARCH_BOSSES),
        entries = filtered,
        entryKey = { it.id },
        emptyText = tr(Tr.NO_MATCHING_BOSS),
        selectedKey = selectedBoss?.id,
        scrollToKey = selectedBoss?.id,
        onPick = onPick,
        header = {
            if (selectedBoss != null) {
                Text(tr(Tr.PICKER_CURRENT_BOSS).format(selectedBoss.displayName(lang)), style = WTypography.labelMedium)
                Spacer(Modifier.height(WDimens.gap))
            }
        },
        row = { entry -> BossResultRow(monster = entry) }
    )
}

@Composable
private fun BossResultRow(monster: Monster) {
    val lang = LocalLang.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MonsterIcon(monster = monster, size = 40.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // The name in the language of the app (the list is sorted by it, and search matches both languages). The level
                // beside it is what tells apart bosses that share a name ("Cire Momore" exists at levels 58, 73 and 233).
                Text(
                    text = monster.displayName(lang),
                    style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${tr(Tr.BOSS_LEVEL_SHORT)} ${monster.level}",
                    style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
                )
            }
            BossResistanceChips(boss = monster)
        }
    }
}

@Composable
private fun ItemRunePickerModal(
    carrier: Equipment,
    runeOptions: List<RuneType>,
    initialSelection: List<Int>,
    onConfirm: (List<Int>) -> Unit,
    onCancel: () -> Unit,
) {
    val lang = LocalLang.current
    val sockets = carrier.maxShardSlots
    // SnapshotStateMap of rune-id -> count, seeded from the runes already pinned onto this item.
    val counts =
        remember(carrier.equipmentId) {
            mutableStateMapOf<Int, Int>().apply {
                initialSelection.groupingBy { it }.eachCount().forEach { (id, n) -> put(id, n) }
            }
        }
    var query by remember { mutableStateOf("") }
    val total = counts.values.sum()
    val filtered =
        remember(query, runeOptions, lang) {
            val q = query.trim()
            runeOptions
                .filter { rune ->
                    q.isBlank() ||
                        rune.name.fr.contains(q, ignoreCase = true) ||
                        rune.name.en.contains(q, ignoreCase = true) ||
                        rune.name.es.contains(q, ignoreCase = true) ||
                        rune.name.pt.contains(q, ignoreCase = true)
                }.sortedByLocalized(lang) { it.name.localized(lang) }
        }
    val carrierName = carrier.name.localized(lang)
    PickerScaffold(
        title = "${tr(Tr.EDIT_RUNES_TITLE)} — $carrierName",
        query = query,
        onQueryChange = { query = it },
        placeholder = tr(Tr.SEARCH_RUNES),
        entries = filtered,
        entryKey = { it.id },
        emptyText = tr(Tr.NO_MATCHING_RUNE),
        listHeight = 360.dp,
        header = {
            Text(
                text = "${tr(Tr.RUNE_SOCKETS_LABEL)}: $total / $sockets",
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
            )
            Spacer(modifier = Modifier.height(WDimens.gap))
        },
        footer = {
            Spacer(modifier = Modifier.height(WDimens.gap))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                DialogButton(text = tr(Tr.CANCEL), filled = false, color = WColor.border, onClick = onCancel, modifier = Modifier.weight(1f))
                DialogButton(
                    text = tr(Tr.SAVE),
                    filled = true,
                    color = WColor.accent,
                    onClick = { onConfirm(counts.entries.flatMap { (id, n) -> List(n) { id } }) },
                    modifier = Modifier.weight(1f)
                )
            }
        },
        row = { rune ->
            RuneOptionRow(
                rune = rune,
                lang = lang,
                carrierItemType = carrier.itemType,
                count = counts[rune.id] ?: 0,
                canAdd = total < sockets,
                onAdd = { counts[rune.id] = (counts[rune.id] ?: 0) + 1 },
                onRemove = {
                    val current = counts[rune.id] ?: 0
                    if (current <= 1) counts.remove(rune.id) else counts[rune.id] = current - 1
                }
            )
        }
    )
}

@Composable
private fun RuneOptionRow(
    rune: RuneType,
    lang: Lang,
    carrierItemType: ItemType,
    count: Int,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    val selected = count > 0
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, if (selected) WColor.accent.copy(alpha = 0.6f) else WColor.border, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(rune.color.pickerColor())
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = rune.name.localized(lang),
                    style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (rune.isDoubledOn(carrierItemType)) {
                    RuneDoubleBadge()
                }
            }
            Text(
                text = rune.characteristic.label(lang),
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        StepperButton(glyph = "−", enabled = count > 0, onClick = onRemove)
        Box(modifier = Modifier.widthIn(min = 16.dp), contentAlignment = Alignment.Center) {
            Text(
                text = count.toString(),
                style = WTypography.labelMedium.copy(color = if (selected) WColor.text else WColor.faint, fontFamily = WType.mono)
            )
        }
        StepperButton(glyph = "＋", enabled = canAdd, onClick = onAdd)
    }
}

@Composable
private fun RuneDoubleBadge() {
    Box(
        modifier =
            Modifier
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(WColor.accent2.copy(alpha = 0.16f))
                .border(1.dp, WColor.accent2.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "×2", style = WTypography.labelSmall.copy(color = WColor.accent2, fontFamily = WType.mono, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun StepperButton(
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(26.dp)
                .alpha(if (enabled) 1f else 0.35f)
                .clip(RoundedCornerShape(7.dp))
                .background(WColor.bg)
                .border(1.dp, WColor.border, RoundedCornerShape(7.dp))
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(text = glyph, style = WTypography.labelMedium.copy(color = WColor.text, lineHeight = 14.sp))
    }
}

@Composable
private fun PickerToggle(
    checked: Boolean,
    label: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (checked) WColor.success else WColor.bg)
                    .border(1.dp, if (checked) WColor.success else WColor.border, RoundedCornerShape(4.dp))
        )
        Text(text = label, style = WTypography.labelMedium.copy(color = WColor.text))
    }
}

@Composable
private fun PickerDoneButton(onDone: () -> Unit) {
    Spacer(modifier = Modifier.height(WDimens.gap))
    DialogButton(
        text = tr(Tr.DONE),
        filled = true,
        color = WColor.accent,
        onClick = onDone,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Socket / rune colour swatch, mirroring the paperdoll's shard colours (red / green / blue). */
private fun RuneColor.pickerColor(): Color =
    when (this) {
        RuneColor.RED -> Color(0xFFE05A5A)
        RuneColor.GREEN -> Color(0xFF5FB76A)
        RuneColor.BLUE -> Color(0xFF5A8FE0)
    }

@Composable
internal fun ModalCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .widthIn(min = 380.dp, max = 460.dp)
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.border, RoundedCornerShape(WDimens.radius))
                .padding(WDimens.pad)
    ) {
        Text(
            text = title,
            style = WTypography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(WDimens.gap))
        content()
    }
}

/**
 * The modals' single-line text input. It never takes the keyboard focus by itself: a form with several fields used to end up
 * with the focus on whichever one was composed LAST (the Save dialog opened on its note field, the Edit dialog on its tags),
 * so the field that should start focused — a picker's only field, a form's first one — asks for it with [autoFocus].
 * [onEnter] runs when Enter is pressed in the field (and consumes the key).
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    autoFocus: Boolean = false,
    onEnter: (() -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }
    // The caller owns the text; the cursor / selection live here. A field that takes the focus on open starts with its whole
    // text selected, so typing replaces a pre-filled name (a plain String field would leave the cursor at position 0, in
    // front of the suggestion, and typing would garble it).
    var field by remember { mutableStateOf(TextFieldValue(text = query, selection = if (autoFocus) TextRange(0, query.length) else TextRange.Zero)) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.bg)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = if (field.text == query) field else field.copy(text = query),
            onValueChange = { updated ->
                field = updated
                if (updated.text != query) onQueryChange(updated.text)
            },
            singleLine = true,
            cursorBrush = SolidColor(WColor.accent),
            textStyle = WTypography.bodyMedium.copy(color = WColor.text),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .then(if (onEnter != null) Modifier.onEnterKey(onEnter) else Modifier)
        )
        if (query.isEmpty()) {
            Text(text = placeholder, style = WTypography.bodyMedium.copy(color = WColor.faint))
        }
    }
}

private fun Key.isEnter(): Boolean = this == Key.Enter || this == Key.NumPadEnter

/** Runs [action] when Enter is pressed and consumes the key. A preview handler, so the text field never sees it first. */
private fun Modifier.onEnterKey(action: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key.isEnter()) {
            action()
            true
        } else {
            false
        }
    }

/** Runs [action] on Ctrl+Enter or Cmd+Enter anywhere inside — the "submit" of a form that has a free-text field. */
private fun Modifier.onSubmitShortcut(action: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key.isEnter() && (event.isCtrlPressed || event.isMetaPressed)) {
            action()
            true
        } else {
            false
        }
    }

@Composable
private fun SaveBuildModal(
    initialName: String,
    isEditingExisting: Boolean,
    takenNames: Set<String>,
    takenNamesForNew: Set<String>,
    onSave: (name: String, note: String?, asNew: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var note by remember { mutableStateOf("") }
    var asNew by remember { mutableStateOf(false) }
    val nameTaken = name.trim().lowercase() in if (asNew) takenNamesForNew else takenNames
    // Block any save whose name collides with a *different* saved build, so two builds never
    // share a name (which would make the library and compare view ambiguous).
    val canSave = name.isNotBlank() && !nameTaken
    // Enter in the name field and Ctrl/Cmd+Enter anywhere in the dialog do what the highlighted button does ("Update" for a
    // loaded build, "Save" otherwise), and nothing while that button is disabled.
    val submit = { if (canSave) onSave(name, note.ifBlank { null }, asNew) }
    ModalCard(title = tr(Tr.SAVE_DIALOG_TITLE), modifier = Modifier.onSubmitShortcut(submit)) {
        LabeledField(
            label = tr(Tr.SAVE_NAME_LABEL),
            value = name,
            onValueChange = { name = it },
            placeholder = "",
            autoFocus = true,
            onEnter = submit
        )
        if (nameTaken) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = tr(Tr.SAVE_NAME_TAKEN),
                style = WTypography.labelSmall.copy(color = WColor.danger)
            )
        }
        Spacer(modifier = Modifier.height(WDimens.gap))
        LabeledField(
            label = tr(Tr.SAVE_NOTE_LABEL),
            value = note,
            onValueChange = { note = it },
            placeholder = ""
        )
        if (isEditingExisting && !asNew) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = tr(Tr.SAVE_UPDATE_HINT),
                style = WTypography.labelSmall.copy(color = WColor.muted)
            )
        }
        Spacer(modifier = Modifier.height(WDimens.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DialogButton(text = tr(Tr.CANCEL), filled = false, color = WColor.border, onClick = onCancel, modifier = Modifier.weight(1f))
            if (isEditingExisting && !asNew) {
                DialogButton(
                    text = tr(Tr.SAVE_AS_NEW),
                    filled = false,
                    color = WColor.accent2,
                    enabled = canSave,
                    onClick = {
                        name = freeBuildName(name, takenNamesForNew)
                        asNew = true
                    },
                    modifier = Modifier.weight(1f)
                )
                DialogButton(
                    text = tr(Tr.UPDATE_BUILD),
                    filled = true,
                    color = WColor.accent,
                    enabled = canSave,
                    onClick = submit,
                    modifier = Modifier.weight(1f)
                )
            } else {
                DialogButton(
                    text = tr(Tr.SAVE),
                    filled = true,
                    color = WColor.accent,
                    enabled = canSave,
                    onClick = submit,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ImportBuildModal(
    validate: (String) -> Boolean,
    clipboardText: () -> String,
    onImport: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val valid = remember(text) { validate(text) }
    val showInvalid = text.isNotBlank() && !valid
    ModalCard(title = tr(Tr.IMPORT_DIALOG_TITLE)) {
        Text(
            text = tr(Tr.IMPORT_DIALOG_HINT),
            style = WTypography.bodyMedium.copy(color = WColor.muted, lineHeight = 19.sp)
        )
        Spacer(modifier = Modifier.height(WDimens.gap))
        MultilineField(value = text, onValueChange = { text = it }, placeholder = tr(Tr.IMPORT_PLACEHOLDER))
        if (showInvalid) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = tr(Tr.IMPORT_INVALID), style = WTypography.labelSmall.copy(color = WColor.danger))
        }
        Spacer(modifier = Modifier.height(WDimens.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            // Convenience: fill the field straight from the clipboard (paste also works via the field).
            DialogButton(text = tr(Tr.IMPORT_PASTE), filled = false, color = WColor.accent2, onClick = { text = clipboardText() })
            Spacer(modifier = Modifier.weight(1f))
            DialogButton(text = tr(Tr.CANCEL), filled = false, color = WColor.border, onClick = onCancel)
            DialogButton(
                text = tr(Tr.IMPORT_CONFIRM),
                filled = true,
                color = WColor.accent,
                enabled = valid,
                onClick = { onImport(text) }
            )
        }
    }
}

/** Scrollable, monospace multi-line input — used by the import dialog to paste an exported build. */
@Composable
private fun MultilineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val scroll = rememberScrollState()
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.bg)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = false,
            cursorBrush = SolidColor(WColor.accent),
            textStyle = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.text, lineHeight = 16.sp),
            modifier = Modifier.fillMaxSize().verticalScroll(scroll)
        )
        if (value.isEmpty()) {
            Text(text = placeholder, style = WTypography.bodyMedium.copy(color = WColor.faint))
        }
    }
}

@Composable
private fun EditBuildModal(
    entry: HistoryEntry,
    takenNames: Set<String>,
    existingFolders: List<String>,
    existingTags: List<String>,
    onSave: (id: String, name: String, note: String?, tags: List<String>, folder: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(entry.name) }
    var note by remember { mutableStateOf(entry.note.orEmpty()) }
    var tags by remember { mutableStateOf(entry.tags) }
    var folder by remember { mutableStateOf(entry.folder) }
    // The build's own name must not count as "taken" (editing it isn't a collision with itself).
    val ownName = entry.name.trim().lowercase()
    val nameTaken = name.trim().lowercase().let { it != ownName && it in takenNames }
    val canSave = name.isNotBlank() && !nameTaken
    // Same keys as the Save dialog: Enter in the name field, Ctrl/Cmd+Enter anywhere (the note, the tags…).
    val submit = { if (canSave) onSave(entry.id, name, note.ifBlank { null }, tags, folder) }

    ModalCard(title = tr(Tr.EDIT_BUILD_TITLE), modifier = Modifier.onSubmitShortcut(submit)) {
        LabeledField(label = tr(Tr.SAVE_NAME_LABEL), value = name, onValueChange = { name = it }, placeholder = "", autoFocus = true, onEnter = submit)
        if (nameTaken) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = tr(Tr.SAVE_NAME_TAKEN), style = WTypography.labelSmall.copy(color = WColor.danger))
        }
        Spacer(modifier = Modifier.height(WDimens.gap))
        LabeledField(label = tr(Tr.SAVE_NOTE_LABEL), value = note, onValueChange = { note = it }, placeholder = "")
        Spacer(modifier = Modifier.height(WDimens.gap))
        Text(text = tr(Tr.TAGS_LABEL), style = WTypography.labelMedium.copy(color = WColor.muted))
        Spacer(modifier = Modifier.height(6.dp))
        TagInput(
            selected = tags,
            known = existingTags,
            onAdd = { tags = normalizeTags(tags + it) },
            onRemove = { removed -> tags = tags.filterNot { it.equals(removed, ignoreCase = true) } }
        )
        Spacer(modifier = Modifier.height(WDimens.gap))
        Text(text = tr(Tr.FOLDER_LABEL), style = WTypography.labelMedium.copy(color = WColor.muted))
        Spacer(modifier = Modifier.height(6.dp))
        FolderPicker(
            current = folder,
            existingFolders = existingFolders,
            onSelect = { folder = it }
        )
        Spacer(modifier = Modifier.height(WDimens.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DialogButton(text = tr(Tr.CANCEL), filled = false, color = WColor.border, onClick = onCancel, modifier = Modifier.weight(1f))
            DialogButton(
                text = tr(Tr.SAVE),
                filled = true,
                color = WColor.accent,
                enabled = canSave,
                onClick = { onSave(entry.id, name, note.ifBlank { null }, tags, folder) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FolderPicker(
    current: String?,
    existingFolders: List<String>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    Column {
        Box {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(WColor.bg)
                        .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = current ?: tr(Tr.FOLDER_NONE),
                    style = WTypography.bodyMedium.copy(color = if (current == null) WColor.faint else WColor.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(text = "▾", style = WTypography.labelSmall.copy(lineHeight = 10.sp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = WColor.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, WColor.border)
            ) {
                DropdownMenuItem(
                    text = { Text(text = tr(Tr.FOLDER_NONE), style = WTypography.bodyMedium.copy(color = if (current == null) WColor.accent else WColor.text)) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                existingFolders.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(text = name, style = WTypography.bodyMedium.copy(color = if (name == current) WColor.accent else WColor.text)) },
                        onClick = {
                            onSelect(name)
                            expanded = false
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(text = tr(Tr.FOLDER_NEW), style = WTypography.bodyMedium.copy(color = WColor.accent2)) },
                    onClick = {
                        creating = true
                        draft = ""
                        expanded = false
                    }
                )
            }
        }
        if (creating) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    SearchField(query = draft, onQueryChange = { draft = it }, placeholder = tr(Tr.FOLDER_NEW), autoFocus = true)
                }
                DialogButton(
                    text = tr(Tr.TAG_ADD),
                    filled = false,
                    color = WColor.accent2,
                    enabled = draft.isNotBlank(),
                    onClick = {
                        onSelect(draft.trim())
                        creating = false
                    }
                )
            }
        }
    }
}

@Composable
private fun RenameValueModal(
    title: String,
    label: String,
    initialName: String,
    onRename: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    ModalCard(title = title) {
        LabeledField(
            label = label,
            value = name,
            onValueChange = { name = it },
            placeholder = "",
            autoFocus = true,
            onEnter = { if (name.isNotBlank()) onRename(name) }
        )
        Spacer(modifier = Modifier.height(WDimens.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DialogButton(text = tr(Tr.CANCEL), filled = false, color = WColor.border, onClick = onCancel, modifier = Modifier.weight(1f))
            DialogButton(
                text = tr(Tr.SAVE),
                filled = true,
                color = WColor.accent,
                enabled = name.isNotBlank(),
                onClick = { onRename(name) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Type-or-select tag input. The currently-assigned tags show as removable chips; the field below
 * filters the known tags as you type (▾ browses them all), and offers to create the typed name when
 * it's new. Tags are first-class entities — removing one here only unassigns it from this build; it
 * lives on until deleted from the library sidebar.
 */
@Composable
internal fun TagInput(
    selected: List<String>,
    known: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var browsing by remember { mutableStateOf(false) }
    val draft = query.trim()
    val (suggestions, canCreate) = tagInputSuggestions(known = known, selected = selected, rawQuery = query)
    val panelOpen = draft.isNotBlank() || browsing

    Column {
        if (selected.isNotEmpty()) {
            selected.chunked(3).forEach { rowTags ->
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 7.dp)) {
                    rowTags.forEach { tag -> RemovableTagChip(label = tag, onRemove = { onRemove(tag) }) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                SearchField(query = query, onQueryChange = { query = it }, placeholder = tr(Tr.TAG_ADD_PLACEHOLDER))
            }
            // ▾ browse: show every known tag without typing.
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (browsing) WColor.raised else WColor.bg)
                        .border(1.dp, if (browsing) WColor.accent.copy(alpha = 0.55f) else WColor.border, RoundedCornerShape(9.dp))
                        .clickable { browsing = !browsing },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "▾", style = WTypography.labelMedium.copy(color = WColor.muted, lineHeight = 12.sp))
            }
        }
        if (panelOpen) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(WColor.bg)
                        .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                        .verticalScroll(rememberScrollState())
            ) {
                if (canCreate) {
                    TagOptionRow(text = "${tr(Tr.TAG_CREATE)} \"$draft\"", glyph = "＋", accent = true, onClick = {
                        onAdd(draft)
                        query = ""
                    })
                }
                suggestions.forEach { tag ->
                    // Keep the panel open (browsing) so several tags can be added in a row.
                    TagOptionRow(text = tag, glyph = "#", accent = false, onClick = { onAdd(tag) })
                }
                if (suggestions.isEmpty() && !canCreate) {
                    Text(
                        text = tr(Tr.TAG_NONE_LEFT),
                        style = WTypography.labelSmall.copy(color = WColor.faint),
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TagOptionRow(
    text: String,
    glyph: String,
    accent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = glyph,
            style = WTypography.labelSmall.copy(color = if (accent) WColor.accent2 else WColor.faint)
        )
        Text(
            text = text,
            style = WTypography.bodyMedium.copy(color = if (accent) WColor.accent2 else WColor.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RemovableTagChip(
    label: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.accent2.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                .padding(start = 9.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = label,
            style = WTypography.labelSmall.copy(color = WColor.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(onClick = onRemove).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "✕", style = WTypography.labelSmall.copy(color = WColor.muted))
        }
    }
}

@Composable
private fun ConfirmModal(
    title: String,
    emphasis: String?,
    message: String,
    confirmLabel: String,
    confirmColor: Color,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalCard(title = title) {
        if (emphasis != null) {
            Text(
                text = emphasis,
                style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        Text(
            text = message,
            style = WTypography.bodyMedium.copy(color = WColor.muted, lineHeight = 19.sp)
        )
        Spacer(modifier = Modifier.height(WDimens.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DialogButton(text = tr(Tr.CANCEL), filled = false, color = WColor.border, onClick = onCancel, modifier = Modifier.weight(1f))
            DialogButton(text = confirmLabel, filled = true, color = confirmColor, onClick = onConfirm, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    autoFocus: Boolean = false,
    onEnter: (() -> Unit)? = null,
) {
    Column {
        Text(text = label, style = WTypography.labelMedium.copy(color = WColor.muted))
        Spacer(modifier = Modifier.height(6.dp))
        SearchField(query = value, onQueryChange = onValueChange, placeholder = placeholder, autoFocus = autoFocus, onEnter = onEnter)
    }
}

@Composable
internal fun DialogButton(
    text: String,
    filled: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .height(42.dp)
                .alpha(if (enabled) 1f else 0.45f)
                .clip(RoundedCornerShape(10.dp))
                .background(if (filled) color else Color.Transparent)
                .border(1.dp, if (filled) color else WColor.border, RoundedCornerShape(10.dp))
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style =
                WTypography.labelLarge.copy(
                    color = if (filled) WColor.bg else WColor.text
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickable(
            interactionSource = MutableInteractionSource(),
            indication = null,
            onClick = onClick
        )
    )
