package com.privatemediavault.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.privatemediavault.data.MediaItem
import com.privatemediavault.ui.theme.GlassCard
import com.privatemediavault.viewmodel.ChangePinFeedback
import com.privatemediavault.viewmodel.SettingsEvent
import com.privatemediavault.viewmodel.SettingsUiState
import com.privatemediavault.viewmodel.SettingsViewModel

/**
 * Settings entry point. Observes [SettingsViewModel.uiState] and renders the reveal-all
 * override, the change-PIN form, the remove-originals toggle, and the explicit lock action.
 * One-shot [SettingsEvent.NavigateToPin] events (explicit lock, Req 9.4; denied export,
 * Req 11.2) are forwarded to [onNavigateToPin] so the host shows the PIN entry screen.
 *
 * @param viewModel       supplies the settings state and the change-PIN/lock/toggle actions.
 * @param onNavigateToPin invoked when the vault locks or a session-gated action is denied.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    // Forward one-shot navigation effects exactly once (Req 9.4, 11.2).
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.NavigateToPin -> onNavigateToPin()
            }
        }
    }

    SettingsContent(
        state = state,
        onChangePin = viewModel::changePin,
        onDismissChangePinFeedback = viewModel::dismissChangePinFeedback,
        onToggleRemoveOriginals = viewModel::setRemoveOriginals,
        onToggleRevealAll = viewModel::setRevealAll,
        onLock = viewModel::lock,
        onConfirmDelete = viewModel::confirmDelete,
        onCancelDelete = viewModel::cancelDelete,
        onDismissStatus = viewModel::dismissStatusMessage,
        modifier = modifier,
    )
}

/**
 * Stateless settings content, separated from the view model for previewing and testing.
 * Holds only the transient text being typed into the change-PIN form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onChangePin: (CharArray, CharArray, CharArray) -> Unit,
    onDismissChangePinFeedback: () -> Unit,
    onToggleRemoveOriginals: (Boolean) -> Unit,
    onToggleRevealAll: (Boolean) -> Unit,
    onLock: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface transient delete/export outcomes as a snackbar, then clear them (Req 10.3, 11.1).
    LaunchedEffect(state.statusMessage) {
        val message = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissStatus()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text("Settings", style = MaterialTheme.typography.titleLarge)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            RevealAllSection(
                revealAll = state.revealAll,
                onToggle = onToggleRevealAll,
            )

            ChangePinSection(
                feedback = state.changePinFeedback,
                onChangePin = onChangePin,
                onDismissFeedback = onDismissChangePinFeedback,
            )

            RemoveOriginalsSection(
                enabled = state.removeOriginals,
                onToggle = onToggleRemoveOriginals,
            )

            SecuritySection(onLock = onLock)
        }
    }

    // Delete confirmation flow (Req 10.1): only delete after the User confirms.
    state.pendingDelete?.let { item ->
        DeleteConfirmationDialog(
            item = item,
            onConfirm = onConfirmDelete,
            onDismiss = onCancelDelete,
        )
    }
}

/**
 * Prominent reveal-all / blur-all override. When ON, every item in the vault grid shows
 * unblurred at once; when OFF, the blurred-by-default behaviour holds. The supporting copy
 * makes clear the override only applies while the vault is unlocked — a lock always re-blurs.
 */
@Composable
private fun RevealAllSection(
    revealAll: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (revealAll) "Reveal all" else "Blur all",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Show every item unblurred at once. Only applies while the vault is " +
                        "unlocked — locking always re-blurs everything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = revealAll,
                onCheckedChange = onToggle,
                colors = vaultSwitchColors(),
            )
        }
    }
}

/**
 * Change-PIN form (Req 12.1–12.3): current, new, and confirmation entries. The submit is
 * enabled only once all three fields have input; feedback covers the wrong-current-PIN
 * (Req 12.2), too-short, mismatch, lockout, and success cases. On success the fields are
 * cleared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangePinSection(
    feedback: ChangePinFeedback,
    onChangePin: (CharArray, CharArray, CharArray) -> Unit,
    onDismissFeedback: () -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    // Clear the fields once a change succeeds so stale digits do not linger on screen.
    LaunchedEffect(feedback) {
        if (feedback is ChangePinFeedback.Success) {
            current = ""
            newPin = ""
            confirm = ""
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Change PIN",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            PinField(
                value = current,
                onValueChange = { current = it; onDismissFeedback() },
                label = "Current PIN",
            )
            PinField(
                value = newPin,
                onValueChange = { newPin = it; onDismissFeedback() },
                label = "New PIN",
            )
            PinField(
                value = confirm,
                onValueChange = { confirm = it; onDismissFeedback() },
                label = "Confirm new PIN",
            )

            val feedbackText = when (feedback) {
                ChangePinFeedback.Idle -> null
                ChangePinFeedback.Success -> "PIN changed successfully."
                is ChangePinFeedback.Error -> feedback.message
            }
            if (feedbackText != null) {
                Text(
                    text = feedbackText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (feedback is ChangePinFeedback.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            Button(
                onClick = {
                    onChangePin(current.toCharArray(), newPin.toCharArray(), confirm.toCharArray())
                },
                enabled = current.isNotEmpty() && newPin.isNotEmpty() && confirm.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Change PIN")
            }
        }
    }
}

/** A single masked, numeric PIN entry field with the vault's glass-friendly tinting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { entered -> onValueChange(entered.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Remove-originals preference (Req 4.4): a labelled switch wired to the shared setting. */
@Composable
private fun RemoveOriginalsSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Remove originals after import",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Delete the source copy from its original location once it is safely " +
                        "imported into the vault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle, colors = vaultSwitchColors())
        }
    }
}

/** Security actions: an explicit lock that ends the session and routes to PIN (Req 9.4). */
@Composable
private fun SecuritySection(onLock: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Security",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Lock the vault now. You will need your PIN to open it again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onLock,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("Lock vault")
            }
        }
    }
}

/** Shared switch colors that read well on the violet glass surfaces. */
@Composable
private fun vaultSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
)

/**
 * Reusable confirmation before a permanent delete (Req 10.1). Surfaced here from the
 * settings view model's pending-delete state, but the same dialog can back delete actions
 * triggered from the grid or viewer.
 */
@Composable
fun DeleteConfirmationDialog(
    item: MediaItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this item?") },
        text = {
            Text(
                "\"${item.displayName}\" will be permanently removed from the vault. " +
                    "This cannot be undone.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
