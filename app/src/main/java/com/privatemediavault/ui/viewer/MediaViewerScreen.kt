package com.privatemediavault.ui.viewer

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.privatemediavault.domain.model.MediaType
import com.privatemediavault.ui.blur.BlurRenderer
import com.privatemediavault.ui.blur.RenderEffectBlurRenderer
import com.privatemediavault.viewmodel.ViewerEvent
import com.privatemediavault.viewmodel.ViewerUiState
import com.privatemediavault.viewmodel.ViewerViewModel

/**
 * Media viewer entry point. Observes [ViewerViewModel.uiState] and renders the viewed item
 * blurred by default, offering unblur/re-blur controls and an explicit lock action.
 *
 * One-shot [ViewerEvent.NavigateToPin] effects (an unblur denied because the session was
 * locked, Req 7.2, or an explicit lock, Req 9.4) are forwarded to [onNavigateToPin] so the
 * host can show the PIN entry screen.
 *
 * @param viewModel       supplies the render state and the unblur/re-blur/lock actions.
 * @param onNavigateToPin invoked when the vault must show the PIN entry screen.
 * @param onBack          invoked when the User leaves the viewer.
 * @param blurRenderer    produces the blur applied to the item in Blurred State (Req 6.1, 6.2).
 */
@Composable
fun MediaViewerScreen(
    viewModel: ViewerViewModel,
    onNavigateToPin: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    blurRenderer: BlurRenderer = RenderEffectBlurRenderer(),
) {
    val state by viewModel.uiState.collectAsState()

    // Forward navigation effects exactly once as they are emitted (Req 7.2, 9.4).
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ViewerEvent.NavigateToPin -> onNavigateToPin()
            }
        }
    }

    MediaViewerContent(
        state = state,
        onUnblur = viewModel::unblur,
        onReblur = viewModel::reblur,
        onLock = viewModel::lock,
        onBack = onBack,
        onDismissError = viewModel::dismissError,
        loadThumbnail = { id -> viewModel.loadBlurredThumbnail(id) },
        blurRenderer = blurRenderer,
        modifier = modifier,
    )
}

/**
 * Stateless viewer content, separated from the view model for previewing and testing.
 *
 * In Blurred State the item's thumbnail is shown under [BlurRenderer.blurModifier] so its
 * content is obscured (Req 6.1, 6.2). In Clear State an image is decoded and shown
 * directly, while a video is handed to ExoPlayer for playback (Req 7.1, 7.3). The control
 * row swaps between Unblur and Re-blur and always offers an explicit Lock action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerContent(
    state: ViewerUiState,
    onUnblur: () -> Unit,
    onReblur: () -> Unit,
    onLock: () -> Unit,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
    loadThumbnail: suspend (String) -> ByteArray?,
    blurRenderer: BlurRenderer,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.item.displayName) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    TextButton(onClick = onLock) { Text("Lock") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator(color = Color.White)

                    state.isClear && state.mediaBytes != null ->
                        ClearMedia(state = state, bytes = state.mediaBytes)

                    else -> BlurredMedia(
                        itemId = state.item.id,
                        loadThumbnail = loadThumbnail,
                        blurRenderer = blurRenderer,
                    )
                }
            }

            ViewerControls(
                isClear = state.isClear,
                isLoading = state.isLoading,
                onUnblur = onUnblur,
                onReblur = onReblur,
            )
        }
    }

    state.errorMessage?.let { message ->
        ViewerErrorDialog(message = message, onDismiss = onDismissError)
    }
}

/** Renders the clear (unblurred) content: an [Image] for photos, ExoPlayer for videos. */
@Composable
private fun ClearMedia(state: ViewerUiState, bytes: ByteArray) {
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

        MediaType.VIDEO -> VideoPlayer(bytes = bytes, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Plays the decrypted video [bytes] with ExoPlayer (Req 7.3). The bytes are fed through an
 * in-memory [ByteArrayDataSource] so the clear video is never written to disk; the player
 * is released when the composable leaves the composition or the bytes change.
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

/** Renders the item's thumbnail under a blur so its content is not discernible (Req 6.2). */
@Composable
private fun BlurredMedia(
    itemId: String,
    loadThumbnail: suspend (String) -> ByteArray?,
    blurRenderer: BlurRenderer,
) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, itemId) {
        val bytes = loadThumbnail(itemId)
        value = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val image = thumbnail
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .then(blurRenderer.blurModifier(RenderEffectBlurRenderer.DEFAULT_BLUR_RADIUS)),
        )
    } else {
        Text(
            text = "Blurred",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Unblur / Re-blur control row. The label and action swap with the current render state. */
@Composable
private fun ViewerControls(
    isClear: Boolean,
    isLoading: Boolean,
    onUnblur: () -> Unit,
    onReblur: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isClear) {
            Button(
                onClick = onReblur,
                modifier = Modifier.weight(1f),
            ) { Text("Re-blur") }
        } else {
            Button(
                onClick = onUnblur,
                enabled = !isLoading,
                modifier = Modifier.weight(1f),
            ) { Text("Unblur") }
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
