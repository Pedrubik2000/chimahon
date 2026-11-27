package eu.kanade.tachiyomi.ui.glass.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.glass.Glass
import eu.kanade.presentation.glass.GlassIcon
import eu.kanade.presentation.glass.Pill
import eu.kanade.presentation.glass.glassPanel
import eu.kanade.tachiyomi.ui.category.CategoryDialog
import eu.kanade.tachiyomi.ui.category.CategoryEvent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.ui.category.CategoryScreenModel
import eu.kanade.tachiyomi.ui.category.CategoryScreenState
import eu.kanade.tachiyomi.ui.glass.title.GlassMenu
import eu.kanade.tachiyomi.ui.glass.title.MenuItem
import kotlinx.collections.immutable.toImmutableList
import sh.calvin.reorderable.ReorderableColumn
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** [list] with the item at [from] moved to [to] (what a drag settles to); the ids the reorder interactor will persist. */
internal fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> {
    if (from !in list.indices || to !in list.indices || from == to) return list
    return list.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Canvas `option-b` card 2: "EDIT CATEGORIES" in the Library sidebar. Reuses the Classic [CategoryScreenModel]
 * (create, rename, delete, reorder, hide) and its dialogs: no new database logic. Manga categories, as in the Classic Library.
 */
@Composable
internal fun GlassCategoryEditor(model: CategoryScreenModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // the same errors the Classic screen toasts
    LaunchedEffect(model) {
        model.events.collect { e -> if (e is CategoryEvent.LocalizedMessage) context.toast(e.stringRes) }
    }
    val state by model.state.collectAsState()
    val success = state as? CategoryScreenState.Success
    val cats = success?.categories.orEmpty()
    // the list being dragged; it follows the database again when nothing is being dragged
    val local = remember { mutableStateListOf<Category>() }
    LaunchedEffect(cats) {
        local.clear()
        local.addAll(cats)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(MR.strings.glass_cats_heading),
            color = Glass.secondary,
            fontSize = Glass.caption,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
        )
        ReorderableColumn(
            list = local.toList(),
            onSettle = { from, to ->
                val moved = local[from]
                val next = moveItem(local.toList(), from, to)
                local.clear()
                local.addAll(next)
                model.changeOrder(moved, to)
            },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { _, category, _ ->
            androidx.compose.runtime.key(category.id) {
                ReorderableItem {
                    CategoryRow(category, model, Modifier.fillMaxWidth(), dragHandle = Modifier.draggableHandle())
                }
            }
        }
        if (success != null && cats.isEmpty()) {
            Text(stringResource(MR.strings.glass_cats_empty), color = Glass.secondary, fontSize = Glass.body)
        }
        Pill(stringResource(MR.strings.glass_cats_new), { model.showDialog(CategoryDialog.Create) }, small = true, icon = "plus")
    }

    when (val dialog = success?.dialog) {
        CategoryDialog.Create -> CategoryCreateDialog(
            onDismissRequest = model::dismissDialog,
            onCreate = model::createCategory,
            categories = cats.map { it.name }.toImmutableList(),
        )
        is CategoryDialog.Rename -> CategoryRenameDialog(
            onDismissRequest = model::dismissDialog,
            onRename = { model.renameCategory(dialog.category, it) },
            categories = cats.map { it.name }.toImmutableList(),
            category = dialog.category.name,
        )
        is CategoryDialog.Delete -> CategoryDeleteDialog(
            onDismissRequest = model::dismissDialog,
            onDelete = { model.deleteCategory(dialog.category.id) },
            category = dialog.category.name,
        )
        null -> Unit
    }
}

@Composable
private fun CategoryRow(category: Category, model: CategoryScreenModel, modifier: Modifier, dragHandle: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.height(Glass.button).glassPanel(Glass.r12).padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(dragHandle.size(36.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIcon("grip", size = 20.dp, tint = Glass.secondary, modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            category.visualName,
            Modifier.weight(1f).padding(horizontal = 6.dp).alpha(if (category.hidden) 0.62f else 1f),
            fontSize = Glass.body,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RowIcon(if (category.hidden) "eyeOff" else "eye", if (category.hidden) stringResource(MR.strings.glass_cats_show) else stringResource(MR.strings.glass_cats_hide)) {
            model.hideCategory(category)
        }
        RowIcon("more", null) { menu = true }
        GlassMenu(menu, { menu = false }) {
            MenuItem(stringResource(MR.strings.glass_cats_rename)) {
                menu = false
                model.showDialog(CategoryDialog.Rename(category))
            }
            MenuItem(stringResource(MR.strings.glass_cats_delete)) {
                menu = false
                model.showDialog(CategoryDialog.Delete(category))
            }
        }
    }
}

@Composable
private fun RowIcon(icon: String, description: String?, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlassIcon(icon, size = 20.dp, tint = Glass.secondary, contentDescription = description)
    }
}
