package com.privatemediavault.ui.viewer

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem as Media3MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.privatemediavault.data.MediaItem
import com.privatemediavault.domain.model.MediaType
import com.privatemediavault.ui.blur.BlurRenderer
import com.privatemediavault.ui.blur.RenderEffectBlurRenderer
import com.privatemediavault.ui.theme.AppBackground
import com.privatemediavault.ui.theme.GlassSurface
import com.privatemediavault.viewmodel.ViewerEvent
import com.privatemediavault.viewmodel.ViewerUiState
import com.privatemediavault.viewmodel.ViewerViewModel

/**
 * Swipeable media gallery. The User opens the gallery at the tapped [items] entry and swipes
 * left/right through every vault item, like a photo gallery.
 *
 * Each page hosts its **own** per-item [ViewerViewModel], obtained from [viewModelFor] keyed by
 * the item id, so every page owns its blur/clear state and its ExoPlayer in isolation. Only the
 * page currently centred plays video; pages scrolled off release their players (the per-page
 * [VideoPlayer] is removed from composition and its `DisposableEffect` releases the player), so
 * no players leak across swipes.
 *
 * The page's blur/clear render state and the unblur/re-blur/lock actions all come from the
 * single-item [ViewerViewModel] (its public API is unchanged): the item starts blurred (Req
 * 6.1), the User unblurs it (Req 7.1) — denied to PIN entry while locked (Req 7.2) — and a video
 * then plays with ExoPlayer (Req 7.3). Re-blur returns the item to Blurred State (Req 8.1) or
 * surfaces a failure that keeps it visible (Req 8.2).
 *
 * One-shot [ViewerEvent.NavigateToPin] effects from the **current** page (a locked unblur, Req
 * 7.2, or an explicit lock, Req 9.4) are forwarded to [onNavigateToPin] exactly once.
 *
 * When [revealAll] is on (and the session is unlocked) the centred page opens already unblurred
 * to mirror the grid's global Reveal All override; otherwise pages stay blurred by default.
 *
 * @param items           every vault item, in grid order, that the gallery can swipe through.
 * @param startIndex      the index of the tapped item; the gallery opens centred on it.
 * @param viewModelFor    supplies the per-item [ViewerViewModel] (keyed by item id) for a page.
 * @param onNavigateToPin invoked when the vault must show the PIN entry screen.
 * @param onBack          invoked when the User leaves the gallery.
 * @param revealAll       the global Reveal All override; opens the centred page unblurred.
 * @param blurRenderer    produces the blur applied to a page in Blurred State (Req 6.1, 6.2).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGalleryScreen(
    items: List<MediaItem>,
    startIndex: Int,
    viewModelFor: @Composable (MediaItem) -> ViewerViewModel,
    onNavigateToPin: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    revealAll: Boolean = false,
    blurRenderer: BlurRenderer = RenderEffectBlurRenderer(),
) {
    if (items.isEmpty()) {
        // Nothing to show (e.g. the vault emptied out): leave immediately.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val safeStart = startIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeStart) { items.size }

    // The page currently centred drives the top bar, the control row, and the event stream.
    val currentItem = items[pagerState.currentPage.coerceIn(0, items.lastIndex)]
    val currentViewModel = viewModelFor(currentItem)
    val currentState by currentViewModel.uiState.collectAsState()

    // Forward the current page's one-shot navigation effects exactly once (Req 7.2, 9.4).
    LaunchedEffect(currentViewModel) {
        currentViewModel.events.collect { event ->
            when (event) {
                ViewerEvent.NavigateToPin -> onNavigateToPin()
            }
        }
    }

    // Reveal All: open the centred page already unblurred while unlocked. unblur() is session-
    // gated and idempotent, so this is a no-op when locked or already clear (Req: Reveal All).
    LaunchedEffect(currentViewModel, revealAll) {
        if (revealAll) currentViewModel.unblur()
    }

    AppBackground(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            key = { page -> items[page].id },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            val pageViewModel = viewModelFor(item)
            val pageState by pageViewModel.uiState.collectAsState()
            GalleryPage(
                state = pageState,
                isActive = page == pagerState.currentPage,
                loadThumbnail = pageViewModel::loadBlurredThumbnail,
                blurRenderer = blurRenderer,
            )
        }

        GalleryTopBar(
            title = currentItem.displayName,
            position = pagerState.currentPage + 1,
            total = items.size,
            onBack = onBack,
            onLock = currentViewModel::lock,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        GalleryControls(
            isClear = currentState.isClear,
            isLoading = currentState.isLoading,
            onUnblur = currentViewModel::unblur,
            onReblur = currentViewModel::reblur,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    currentState.errorMessage?.let { message ->
        ViewerErrorDialog(message = message, onDismiss = currentViewModel::dismissError)
    }
}

/**
 * A single gallery page's media surface. The item's thumbnail sits underneath an animated blur
 * whose radius eases to zero as the page is cleared and back to the default when blurred (Req
 * 6.1, 6.2); the clear content (a decoded image, or ExoPlayer for video) crossfades in on top.
 *
 * Only the centred page ([isActive]) builds an ExoPlayer, so off-screen video pages hold no
 * player; when a page is swiped away its [VideoPlayer] leaves composition and releases.
 */
@Composable
private fun GalleryPage(
    state: ViewerUiState,
    isActive: Boolean,
    loadThumbnail: suspend (String) -> ByteArray?,
    blurRenderer: BlurRenderer,
) {
    val itemId = state.item.id
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, itemId) {
        val bytes = loadThumbnail(itemId)
        value = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }

    val blurRadius by animateDpAsState(
        targetValue = if (state.isClear) 0.dp else RenderEffectBlurRenderer.DEFAULT_BLUR_RADIUS,
        animationSpec = tween(durationMillis = 320),
        label = "viewerBlur",
    )

    val clearBytes = state.mediaBytes

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // Base layer: the blurred thumbnail (Req 6.2). Stays mounted so the blur can ease out.
        val thumb = thumbnail
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .then(blurRenderer.blurModifier(blurRadius)),
            )
        } else if (!state.isClear) {
            Text(
                text = "Blurred",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        // Clear layer: crossfades in over the thumbnail once the page reaches Clear State.
        Crossfade(
            targetState = state.isClear && clearBytes != null,
            label = "clearFade",
        ) { showClear ->
            if (showClear && clearBytes != null) {
                ClearMedia(state = state, bytes = clearBytes, isActive = isActive)
            } else {
                // Nothing on top; the blurred thumbnail underneath remains visible.
                Box(modifier = Modifier.fillMaxSize())
            }
        }

        if (state.isLoading) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/** Renders the clear (unblurred) content: an [Image] for photos, ExoPlayer for active videos. */
@Composable
private fun ClearMedia(state: ViewerUiState, bytes: ByteArray, isActive: Boolean) {
    when (state.item.mediaType) {
        MediaType.IMAGE -> {
            val image by produceState<ImageBitmap?>(initialValue = null, bytes) {
                value = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = state.item.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Only the centred page plays; an off-screen video renders no player so none leak.
        MediaType.VIDEO ->
            if (isActive) {
                VideoPlayer(bytes = bytes, modifier = Modifier.fillMaxSize())
            } else {
                Box(modifier = Modifier.fillMaxSize())
            }
    }
}

/**
 * Plays the decrypted video [bytes] with ExoPlayer (Req 7.3). The bytes are fed through an
 * in-memory [ByteArrayDataSource] so the clear video is never written to disk; the player is
 * released when the composable leaves the composition (e.g. the page is swiped away) or the
 * bytes change, so no player survives off-screen.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(bytes: ByteArray, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(bytes) {
        ExoPlayer.Builder(context).build().apply {
            val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(bytes) }
            val source = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(Media3MediaItem.fromUri("bytes:///vault-video"))
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = modifier,
    )
}

/** Glassy translucent top bar: Back, the item title, a "position / total" indicator, and Lock. */
@Composable
private fun GalleryTopBar(
    title: String,
    position: Int,
    total: Int,
    onBack: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onBack) {
                Text("Back", color = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                )
                Text(
                    text = "$position / $total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(onClick = onLock) {
                Text("Lock", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Glassy bottom control bar for the centred page. The label and action swap between Unblur and
 * Re-blur with the page's render state (Req 7.1, 8.1).
 */
@Composable
private fun GalleryControls(
    isClear: Boolean,
    isLoading: Boolean,
    onUnblur: () -> Unit,
    onReblur: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            if (isClear) {
                Button(
                    onClick = onReblur,
                    colors = colors,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("Re-blur") }
            } else {
                Button(
                    onClick = onUnblur,
                    enabled = !isLoading,
                    colors = colors,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("Unblur") }
            }
        }
    }
}

/** Reports a failure such as a re-blur that left the item visible (Req 8.2). */
@Composable
private fun ViewerErrorDialog(message: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Something went wrong",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Start,
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    OutlinedButton(onClick = onDismiss) { Text("OK") }
                }
            }
        }
    }
}
