package com.privatemediavault.ui.vault

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.privatemediavault.data.MediaItem
import com.privatemediavault.domain.model.FailedImport
import com.privatemediavault.ui.blur.BlurRenderer
import com.privatemediavault.ui.blur.RenderEffectBlurRenderer
import com.privatemediavault.ui.theme.GlassCard
import com.privatemediavault.ui.theme.glass
import com.privatemediavault.viewmodel.VaultGridItem
import com.privatemediavault.viewmodel.VaultUiState
import com.privatemediavault.viewmodel.VaultViewModel

/** Corner radius for the glass grid cells. */
private val CellCornerRadius = 18.dp

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
 * Every cell renders its decrypted thumbnail under an animated blur whose radius eases to
 * zero when the item is cleared and back to [RenderEffectBlurRenderer.DEFAULT_BLUR_RADIUS]
 * when blurred (Req 6.1, 6.2). The import button launches the system visual-media picker;
 * the returned `Uri`s are handed straight to [onImport].
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
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Vault",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                actions = {
                    TextButton(onClick = onOpenSettings) {
                        Text("Settings", color = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(if (state.isImporting) "Importing\u2026" else "Import") },
                icon = { Text("+", fontSize = 22.sp) },
                onClick = {
                    pickMedia.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                        ),
                    )
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
            )
        },
    ) { padding ->
        if (state.items.isEmpty()) {
            EmptyVault(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
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
 * A single glass grid cell. The decrypted thumbnail is loaded lazily and crossfades in, then
 * rendered under an animated blur: the radius eases to zero in Clear State and back to the
 * default radius in Blurred State, so unblur/re-blur transitions are smooth rather than
 * snapping (Req 6.1).
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

    val blurRadius by animateDpAsState(
        targetValue = if (gridItem.renderState.isClear) {
            0.dp
        } else {
            RenderEffectBlurRenderer.DEFAULT_BLUR_RADIUS
        },
        animationSpec = tween(durationMillis = 320),
        label = "cellBlur",
    )

    val shape = RoundedCornerShape(CellCornerRadius)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .glass(shape = shape, sheen = false)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = thumbnail, label = "thumbFade") { image ->
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .then(blurRenderer.blurModifier(blurRadius)),
                )
            } else {
                // Loading / placeholder state.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

@Composable
private fun EmptyVault(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        GlassCard(onContentPadding = 28.dp) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .glass(shape = CircleShape, sheen = true),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "\uD83D\uDDBC", fontSize = 26.sp)
                }
                Text(
                    text = "Your vault is empty",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Tap Import to add photos or videos. Everything stays encrypted and " +
                        "blurred by default.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
        GlassCard(fill = MaterialTheme.colorScheme.surface) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Some files could not be imported",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
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
