package com.privatemediavault.ui.vault

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.privatemediavault.data.MediaItem
import com.privatemediavault.domain.model.FailedImport
import com.privatemediavault.ui.blur.BlurRenderer
import com.privatemediavault.ui.blur.RenderEffectBlurRenderer
import com.privatemediavault.viewmodel.VaultGridItem
import com.privatemediavault.viewmodel.VaultUiState
import com.privatemediavault.viewmodel.VaultViewModel

/**
 * Vault grid entry point. Observes [VaultViewModel.uiState] and renders the blurred-by-
 * default grid, the import action (system photo/video picker), and any per-file import
 * failures.
 *
 * Selecting an item invokes [onOpenItem] so the host can navigate to the media viewer
 * (task 9.4); this screen only exposes the selection hook and never clears an item itself.
 * [onOpenSettings] navigates to the settings screen (PIN change, lock, remove-originals).
 *
 * @param viewModel       supplies the grid state, import action, and thumbnail decryption.
 * @param onOpenItem      selection hook fired when the user taps an item.
 * @param onOpenSettings  invoked when the user opens settings from the app bar.
 * @param blurRenderer    produces the blur modifier applied to every thumbnail (Req 6.1, 6.2).
 */
@Composable
fun VaultGridScreen(
    viewModel: VaultViewModel,
    onOpenItem: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    blurRenderer: BlurRenderer = RenderEffectBlurRenderer(),
) {
    val state by viewModel.uiState.collectAsState()
    VaultGridContent(
        state = state,
        onImport = viewModel::importFromPicker,
        onOpenItem = onOpenItem,
        onOpenSettings = onOpenSettings,
        onDismissErrors = viewModel::dismissImportErrors,
        loadThumbnail = viewModel::loadThumbnail,
        blurRenderer = blurRenderer,
        modifier = modifier,
    )
}

/**
 * Stateless grid content, separated from the view model for previewing and testing.
 *
 * Every cell renders its decrypted thumbnail under [BlurRenderer.blurModifier], so the
 * content is obscured by default (Req 6.1, 6.2). The import button launches the system
 * visual-media picker; the returned `Uri`s are handed straight to [onImport].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultGridContent(
    state: VaultUiState,
    onImport: (List<android.net.Uri>) -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    onDismissErrors: () -> Unit,
    loadThumbnail: suspend (String) -> ByteArray?,
    blurRenderer: BlurRenderer,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> onImport(uris) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Vault") },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(if (state.isImporting) "Importing\u2026" else "Import") },
                icon = {},
                onClick = {
                    pickMedia.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        if (state.items.isEmpty()) {
            EmptyVault(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.item.id }) { gridItem ->
                    VaultGridCell(
                        gridItem = gridItem,
                        loadThumbnail = loadThumbnail,
                        blurRenderer = blurRenderer,
                        onClick = { onOpenItem(gridItem.item) },
                    )
                }
            }
        }
    }

    if (state.importErrors.isNotEmpty()) {
        ImportErrorDialog(errors = state.importErrors, onDismiss = onDismissErrors)
    }
}

/**
 * A single grid cell. The decrypted thumbnail is loaded lazily and rendered under
 * [BlurRenderer.blurModifier] whenever the item is in Blurred State (the default for the
 * grid, Req 6.1). Tapping the cell signals selection for the viewer.
 */
@Composable
private fun VaultGridCell(
    gridItem: VaultGridItem,
    loadThumbnail: suspend (String) -> ByteArray?,
    blurRenderer: BlurRenderer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val id = gridItem.item.id
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, id) {
        val bytes = loadThumbnail(id)
        value = bytes?.let {
            BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val image = thumbnail
        if (image != null) {
            // The grid always shows items blurred by default; the blur modifier is applied
            // unless the item has been explicitly cleared (which only happens in the viewer).
            val imageModifier = if (gridItem.renderState.isClear) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxSize()
                    .then(blurRenderer.blurModifier(RenderEffectBlurRenderer.DEFAULT_BLUR_RADIUS))
            }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
        }
    }
}

@Composable
private fun EmptyVault(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "Your vault is empty. Tap Import to add photos or videos.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Reports which files failed to import while the rest of the batch succeeded (Req 4.3).
 */
@Composable
private fun ImportErrorDialog(
    errors: List<FailedImport>,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Some files could not be imported",
                    style = MaterialTheme.typography.titleMedium,
                )
                errors.forEach { failure ->
                    Text(
                        text = "\u2022 ${failure.sourceName}: ${failure.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}
