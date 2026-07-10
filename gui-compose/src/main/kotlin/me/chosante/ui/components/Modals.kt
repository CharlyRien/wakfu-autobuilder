package me.chosante.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
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
    runePickerCarrier: Equipment? = null,
    runeOptions: List<RuneType> = emptyList(),
    initialPinnedRunes: List<Int> = emptyList(),
    onConfirmItemRunes: (itemName: String, runeIds: List<Int>) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    suggestedSaveName: String = "",
    isEditingExisting: Boolean = false,
    takenNames: Set<String> = emptySet(),
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
                    excluded = excludedCharacteristics,
                    onSelect = onSelectStat,
                    onDone = onDismiss
                )

            is Modal.ItemPicker ->
                ItemPickerModal(
                    mode = modal.mode,
                    equipmentCatalog = equipmentCatalog,
                    selectedNames = forcedItemNames + excludedItemNames,
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
                    selectedNames = forcedSublimations + excludedSublimations,
                    onPick = onPickSublimation,
                    onDone = onDismiss
                )

            Modal.PassivePicker ->
                PassivePickerModal(clazz = passiveClass, selectedNames = forcedPassives, onPick = onPickPassive, onDone = onDismiss)

            Modal.BossPicker ->
                BossPickerModal(onPick = onPickBoss)

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
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color(0xCC0B0C0F))
                .noRippleClickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        // Card swallows its own clicks so it does not dismiss the scrim.
        Box(modifier = Modifier.noRippleClickable {}) {
            content()
        }
    }
}

@Composable
private fun AddStatModal(
    excluded: Set<Characteristic>,
    onSelect: (Characteristic) -> Unit,
    onDone: () -> Unit,
) {
    val lang = LocalLang.current
    var query by remember { mutableStateOf("") }
    val sections =
        remember(query, excluded, lang) {
            val normalizedQuery = query.trim()
            val results =
                statCatalog.filter { def ->
                    def.characteristic !in excluded &&
                        def.label(lang).contains(normalizedQuery, ignoreCase = true)
                }
            statSections.mapNotNull { section ->
                val sectionStats =
                    results
                        .filter { section.accepts(it.characteristic) }
                        .sortedByLocalized(lang) { it.label(lang) }
                if (sectionStats.isEmpty()) {
                    null
                } else {
                    section to sectionStats
                }
            }
        }
    ModalCard(title = tr(Tr.ADD_TARGET_STAT_TITLE)) {
        SearchField(query = query, onQueryChange = { query = it }, placeholder = tr(Tr.FILTER_STATS))
        Spacer(modifier = Modifier.height(WDimens.gap))
        Column(
            modifier =
                Modifier
                    .heightIn(max = 420.dp)
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
                            CatalogTile(
                                characteristic = def.characteristic,
                                glyph = def.glyph,
                                color = def.color,
                                label = def.label(lang),
                                onClick = { onSelect(def.characteristic) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (sections.isEmpty()) {
                Text(
                    text = tr(Tr.NO_MATCHING_STAT),
                    style = WTypography.bodyMedium.copy(color = WColor.muted),
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
        PickerDoneButton(onDone = onDone)
    }
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .height(44.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onClick)
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
    selectedNames: Set<String>,
    level: Int,
    minLevel: Int,
    maxRarity: Rarity,
    excludedRarities: Set<Rarity>,
    onPick: (Equipment) -> Unit,
    onDone: () -> Unit,
) {
    val lang = LocalLang.current
    var query by remember { mutableStateOf("") }
    var equippableOnly by remember { mutableStateOf(true) }
    val results =
        remember(query, equipmentCatalog, selectedNames, level, minLevel, maxRarity, excludedRarities, equippableOnly, lang) {
            val catalog = equipmentCatalog ?: return@remember emptyList()
            val q = query.trim()
            catalog
                .asSequence()
                .filterNot { it.name.fr in selectedNames }
                .filter { !equippableOnly || it.isEquippableForPicker(level, minLevel, maxRarity, excludedRarities) }
                .filter { equipment ->
                    q.isBlank() ||
                        equipment.name.fr.contains(q, ignoreCase = true) ||
                        equipment.name.en.contains(q, ignoreCase = true)
                }.toList()
                .sortedByLocalized(lang) { it.localizedName(lang) }
                .take(if (q.isBlank()) 60 else 120)
        }
    val title = if (mode == PickerMode.Forced) tr(Tr.REQUIRE_ITEM_TITLE) else tr(Tr.BAN_ITEM_TITLE)
    val accent = if (mode == PickerMode.Forced) WColor.success else WColor.danger
    ModalCard(title = title) {
        if (equipmentCatalog == null) {
            LoadingState(message = tr(Tr.LOADING_ITEMS))
            return@ModalCard
        }
        PickerToggle(
            checked = equippableOnly,
            label = tr(Tr.EQUIPPABLE_ONLY),
            onToggle = { equippableOnly = !equippableOnly }
        )
        Spacer(modifier = Modifier.height(WDimens.gap))
        SearchField(query = query, onQueryChange = { query = it }, placeholder = tr(Tr.SEARCH_ITEMS))
        Spacer(modifier = Modifier.height(WDimens.gap))
        LazyColumn(
            modifier = Modifier.heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(results, key = { it.equipmentId }) { equipment ->
                ItemResultRow(equipment = equipment, mode = mode, accent = accent, onClick = { onPick(equipment) })
            }
        }
        if (results.isEmpty()) {
            Text(
                text = tr(Tr.NO_MATCHING_ITEM),
                style = WTypography.bodyMedium.copy(color = WColor.muted),
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
        PickerDoneButton(onDone = onDone)
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

@Composable
private fun ItemResultRow(
    equipment: Equipment,
    mode: PickerMode,
    accent: Color,
    onClick: () -> Unit,
) {
    val lang = LocalLang.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ItemThumbnail(equipment = equipment, size = 38.dp)
        Column(modifier = Modifier.weight(1f)) {
            val name = if (lang == Lang.FR) equipment.name.fr.ifBlank { equipment.name.en } else equipment.name.en.ifBlank { equipment.name.fr }
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
            Text(
                text = "Lv ${equipment.level} · ${equipment.itemType.label(lang)} · ${equipment.rarity.label(lang)}",
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = tr(if (mode == PickerMode.Forced) Tr.REQUIRE else Tr.BAN),
            style = WTypography.labelMedium.copy(color = accent)
        )
    }
}

@Composable
private fun SublimationPickerModal(
    exclude: Boolean = false,
    selectedNames: List<String>,
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
                .filterNot { it.name.fr in selectedNames }
                .filter { rarityFilter == null || it.rarity == rarityFilter }
                .filter { sub ->
                    q.isBlank() ||
                        sub.name.fr.contains(q, ignoreCase = true) ||
                        sub.name.en.contains(q, ignoreCase = true) ||
                        sublimationEffectText(sub, lang).contains(q, ignoreCase = true)
                }.toList()
                .sortedWith(
                    compareBy<Sublimation> { it.rarity.sortOrder() }
                        .thenComparator { left, right ->
                            localizedCollator(lang).compare(left.name.localized(lang), right.name.localized(lang))
                        }
                ).take(120)
        }
    ModalCard(title = tr(if (exclude) Tr.EXCLUDE_SUBLIMATION_TITLE else Tr.REQUIRE_SUBLIMATION_TITLE)) {
        SublimationRarityFilter(selected = rarityFilter, onSelect = { rarityFilter = it })
        Spacer(modifier = Modifier.height(WDimens.gap))
        SearchField(query = query, onQueryChange = { query = it }, placeholder = tr(Tr.SEARCH_SUBLIMATIONS))
        Spacer(modifier = Modifier.height(WDimens.gap))
        LazyColumn(
            modifier = Modifier.heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filtered, key = { it.stateId }) { sub ->
                SublimationResultRow(sub = sub, lang = lang, onClick = { onPick(sub) })
            }
        }
        if (filtered.isEmpty()) {
            Text(
                text = tr(Tr.NO_MATCHING_SUBLIMATION),
                style = WTypography.bodyMedium.copy(color = WColor.muted),
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
        PickerDoneButton(onDone = onDone)
    }
}

@Composable
private fun SublimationResultRow(
    sub: Sublimation,
    lang: Lang,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (lang == Lang.FR) sub.name.fr.ifBlank { sub.name.en } else sub.name.en.ifBlank { sub.name.fr },
                style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = sub.rarity.name,
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = sub.rarity.displayColor())
            )
            Text(
                // The GENERATION tier (the name's I/II/III) — matches how the level cap filters, so a
                // "Tier 3" sub visibly matches the "≤ 3" cap (the shard upgrade level maxTier is internal).
                text = tr(Tr.SUBLIMATION_TIER_SHORT).format(sub.nameTier),
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
            )
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
        SublimationRarityChip(label = tr(Tr.RARITY_ALL), selected = selected == null, color = WColor.accent, onClick = { onSelect(null) })
        listOf(SublimationRarity.NORMAL, SublimationRarity.EPIC, SublimationRarity.RELIC).forEach { rarity ->
            SublimationRarityChip(
                label = rarity.label(LocalLang.current),
                selected = selected == rarity,
                color = rarity.displayColor(),
                onClick = { onSelect(rarity) }
            )
        }
    }
}

@Composable
private fun SublimationRarityChip(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
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
        Text(
            text = label,
            style = WTypography.labelSmall.copy(color = if (selected) color else WColor.muted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        )
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

private fun SublimationRarity.label(lang: Lang): String =
    when (this) {
        SublimationRarity.EPIC -> if (lang == Lang.FR) "Épique" else "Epic"
        SublimationRarity.RELIC -> if (lang == Lang.FR) "Relique" else "Relic"
        SublimationRarity.NORMAL -> if (lang == Lang.FR) "Normal" else "Normal"
    }

@Composable
private fun PassivePickerModal(
    clazz: me.chosante.common.CharacterClass,
    selectedNames: List<String>,
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
                .filterNot { passive -> passive.name?.fr?.let { it in selectedNames } == true }
                .filter { passive ->
                    q.isBlank() ||
                        passive.name?.localized(lang)?.contains(q, ignoreCase = true) == true ||
                        passive.description?.localized(lang)?.contains(q, ignoreCase = true) == true
                }.toList()
                .sortedByLocalized(lang) { it.name?.localized(lang).orEmpty() }
                .take(120)
        }
    ModalCard(title = tr(Tr.REQUIRE_PASSIVE_TITLE)) {
        SearchField(query = query, onQueryChange = { query = it }, placeholder = tr(Tr.SEARCH_PASSIVES))
        Spacer(modifier = Modifier.height(WDimens.gap))
        LazyColumn(
            modifier = Modifier.heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filtered, key = { it.spellId }) { passive ->
                PassiveResultRow(passive = passive, onClick = { onPick(passive) })
            }
        }
        if (filtered.isEmpty()) {
            Text(
                text = tr(Tr.NO_MATCHING_PASSIVE),
                style = WTypography.bodyMedium.copy(color = WColor.muted),
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
        PickerDoneButton(onDone = onDone)
    }
}

@Composable
private fun PassiveResultRow(
    passive: me.chosante.common.Passive,
    onClick: () -> Unit,
) {
    val lang = LocalLang.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onClick)
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
private fun BossPickerModal(onPick: (Monster) -> Unit) {
    val lang = LocalLang.current
    val results =
        remember(lang) {
            WakfuBestBuildFinderAlgorithm.monsters
                // Boss mode targets bosses, not every creature in the bestiary — keep only boss-tier
                // entries (rank ≥ 1: bosses, golems, ultimate/"Dominant" variants): ~226 of the ~2841 in
                // the full bdata-sourced bestiary (regular monsters are intentionally hidden from the picker).
                .filter { it.isBoss }
                .sortedByLocalized(lang) { it.name.localized(lang) }
        }
    var query by remember { mutableStateOf("") }
    val filtered =
        remember(query, results) {
            val q = query.trim()
            if (q.isBlank()) {
                results
            } else {
                results.filter {
                    it.name.fr.contains(q, ignoreCase = true) || it.name.en.contains(q, ignoreCase = true)
                }
            }.take(120)
        }
    ModalCard(title = tr(Tr.CHOOSE_BOSS_TITLE)) {
        SearchField(query = query, onQueryChange = { query = it }, placeholder = tr(Tr.SEARCH_BOSSES))
        Spacer(modifier = Modifier.height(WDimens.gap))
        LazyColumn(
            modifier = Modifier.heightIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filtered, key = { it.id }) { monster ->
                BossResultRow(monster = monster, onClick = { onPick(monster) })
            }
        }
        if (filtered.isEmpty()) {
            Text(
                text = tr(Tr.NO_MATCHING_BOSS),
                style = WTypography.bodyMedium.copy(color = WColor.muted),
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun BossResultRow(
    monster: Monster,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .clickable(onClick = onClick)
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
                // The bestiary's English names are lowercased; French is the canonical proper-cased form
                // (and search matches both), so we display the French name regardless of UI language.
                Text(
                    text = monster.name.fr.ifBlank { monster.name.en },
                    style = WTypography.bodyMedium.copy(color = WColor.text, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Lv ${monster.level}",
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
                        rune.name.en.contains(q, ignoreCase = true)
                }.sortedByLocalized(lang) { it.name.localized(lang) }
        }
    val carrierName = if (lang == Lang.FR) carrier.name.fr.ifBlank { carrier.name.en } else carrier.name.en.ifBlank { carrier.name.fr }
    ModalCard(title = "${tr(Tr.EDIT_RUNES_TITLE)} — $carrierName") {
        Text(
            text = "${tr(Tr.RUNE_SOCKETS_LABEL)}: $total / $sockets",
            style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
        )
        Spacer(modifier = Modifier.height(WDimens.gap))
        SearchField(query = query, onQueryChange = { query = it }, placeholder = tr(Tr.SEARCH_RUNES))
        Spacer(modifier = Modifier.height(WDimens.gap))
        LazyColumn(
            modifier = Modifier.heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filtered, key = { it.id }) { rune ->
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
        }
        if (filtered.isEmpty()) {
            Text(
                text = tr(Tr.NO_MATCHING_RUNE),
                style = WTypography.bodyMedium.copy(color = WColor.muted),
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
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
    }
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
                .clickable(onClick = onToggle)
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
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
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

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
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
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            cursorBrush = SolidColor(WColor.accent),
            textStyle = WTypography.bodyMedium.copy(color = WColor.text),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
        )
        if (query.isEmpty()) {
            Text(text = placeholder, style = WTypography.bodyMedium.copy(color = WColor.faint))
        }
    }
}

@Composable
private fun SaveBuildModal(
    initialName: String,
    isEditingExisting: Boolean,
    takenNames: Set<String>,
    onSave: (name: String, note: String?, asNew: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var note by remember { mutableStateOf("") }
    val nameTaken = name.trim().lowercase() in takenNames
    ModalCard(title = tr(Tr.SAVE_DIALOG_TITLE)) {
        LabeledField(
            label = tr(Tr.SAVE_NAME_LABEL),
            value = name,
            onValueChange = { name = it },
            placeholder = ""
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
        if (isEditingExisting) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = tr(Tr.SAVE_UPDATE_HINT),
                style = WTypography.labelSmall.copy(color = WColor.muted)
            )
        }
        Spacer(modifier = Modifier.height(WDimens.gap))
        // Block any save whose name collides with a *different* saved build, so two builds never
        // share a name (which would make the library and compare view ambiguous).
        val canSave = name.isNotBlank() && !nameTaken
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DialogButton(text = tr(Tr.CANCEL), filled = false, color = WColor.border, onClick = onCancel, modifier = Modifier.weight(1f))
            if (isEditingExisting) {
                DialogButton(
                    text = tr(Tr.SAVE_AS_NEW),
                    filled = false,
                    color = WColor.accent2,
                    enabled = canSave,
                    onClick = { onSave(name, note.ifBlank { null }, true) },
                    modifier = Modifier.weight(1f)
                )
                DialogButton(
                    text = tr(Tr.UPDATE_BUILD),
                    filled = true,
                    color = WColor.accent,
                    enabled = canSave,
                    onClick = { onSave(name, note.ifBlank { null }, false) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                DialogButton(
                    text = tr(Tr.SAVE),
                    filled = true,
                    color = WColor.accent,
                    enabled = canSave,
                    onClick = { onSave(name, note.ifBlank { null }, false) },
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

    ModalCard(title = tr(Tr.EDIT_BUILD_TITLE)) {
        LabeledField(label = tr(Tr.SAVE_NAME_LABEL), value = name, onValueChange = { name = it }, placeholder = "")
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
                enabled = name.isNotBlank() && !nameTaken,
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
                    SearchField(query = draft, onQueryChange = { draft = it }, placeholder = tr(Tr.FOLDER_NEW))
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
        LabeledField(label = label, value = name, onValueChange = { name = it }, placeholder = "")
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
) {
    Column {
        Text(text = label, style = WTypography.labelMedium.copy(color = WColor.muted))
        Spacer(modifier = Modifier.height(6.dp))
        SearchField(query = value, onQueryChange = onValueChange, placeholder = placeholder)
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
