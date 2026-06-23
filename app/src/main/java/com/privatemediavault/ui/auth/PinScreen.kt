package com.privatemediavault.ui.auth

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatemediavault.ui.theme.GlassBorder
import com.privatemediavault.ui.theme.GlassFill
import com.privatemediavault.ui.theme.glass
import com.privatemediavault.viewmodel.AuthUiState
import com.privatemediavault.viewmodel.AuthViewModel

/** Maximum number of digits the keypad will accept for a single entry. */
private const val MAX_PIN_DIGITS = 12

/** Baseline number of dots shown even before a digit is typed, hinting at a 4+ digit PIN. */
private const val BASELINE_PIN_DOTS = 4

/**
 * PIN screen entry point. Observes [AuthViewModel.uiState] and renders the matching mode
 * (creation, entry, or lockout). When the state becomes [AuthUiState.Unlocked] it invokes
 * [onUnlocked] so the host can navigate into the vault.
 */
@Composable
fun PinScreen(
    viewModel: AuthViewModel,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    PinScreenContent(
        state = state,
        onCreatePin = viewModel::submitNewPin,
        onEnterPin = viewModel::submitPin,
        onUnlocked = onUnlocked,
        modifier = modifier,
    )
}

/**
 * Stateless content for the PIN screen, separated from the view model for previewing and
 * testing. Holds only transient input state (the digits being typed and, during creation,
 * the first PIN awaiting confirmation).
 */
@Composable
fun PinScreenContent(
    state: AuthUiState,
    onCreatePin: (CharArray, CharArray) -> Unit,
    onEnterPin: (CharArray) -> Unit,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Navigate away exactly once when the session unlocks (Req 2.2).
    LaunchedEffect(state) {
        if (state is AuthUiState.Unlocked) onUnlocked()
    }

    var entry by rememberSaveable { mutableStateOf("") }
    // During creation, the first PIN is held here while the user re-enters it (Req 1.3).
    var firstPin by rememberSaveable { mutableStateOf<String?>(null) }

    val isCreating = state is AuthUiState.Creating
    val isLockedOut = state is AuthUiState.LockedOut
    val inputEnabled = !isLockedOut && state !is AuthUiState.Unlocked

    val title = when {
        isCreating && firstPin == null -> "Create a PIN"
        isCreating -> "Confirm your PIN"
        isLockedOut -> "Locked"
        else -> "Enter your PIN"
    }

    val message = when (state) {
        is AuthUiState.Creating -> state.message
            ?: if (firstPin == null) {
                "Choose a PIN of at least 4 digits."
            } else {
                "Re-enter the same PIN to confirm."
            }

        is AuthUiState.Entering -> state.message
        is AuthUiState.LockedOut ->
            "Too many incorrect attempts. Try again in ${state.remainingSeconds}s."

        AuthUiState.Unlocked -> null
    }

    val isError = state is AuthUiState.Entering && state.message != null

    fun submit() {
        if (!inputEnabled || entry.isEmpty()) return
        if (isCreating) {
            val pending = firstPin
            if (pending == null) {
                // First entry captured; move to the confirmation step.
                firstPin = entry
                entry = ""
            } else {
                onCreatePin(pending.toCharArray(), entry.toCharArray())
                // Reset so a mismatch restarts both entries (Req 1.4).
                firstPin = null
                entry = ""
            }
        } else {
            onEnterPin(entry.toCharArray())
            entry = ""
        }
    }

    // Transparent over the AppBackground gradient supplied by the activity.
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Lock badge to anchor the screen visually.
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .glass(shape = CircleShape, sheen = true),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "\uD83D\uDD12", fontSize = 26.sp)
            }

            Spacer(Modifier.height(24.dp))

            Crossfade(targetState = title, label = "pinTitle") { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(28.dp))

            PinDots(count = entry.length, isError = isError)

            Spacer(Modifier.height(16.dp))

            Text(
                text = message ?: " ",
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))

            NumericKeypad(
                enabled = inputEnabled,
                onDigit = { digit ->
                    if (entry.length < MAX_PIN_DIGITS) entry += digit
                },
                onBackspace = { if (entry.isNotEmpty()) entry = entry.dropLast(1) },
            )

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = ::submit,
                enabled = inputEnabled && entry.isNotEmpty(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .width(280.dp)
                    .height(54.dp),
            ) {
                Text(
                    text = when {
                        isCreating && firstPin == null -> "Continue"
                        isCreating -> "Create PIN"
                        else -> "Unlock"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Renders one filled dot per entered digit over a baseline of hollow placeholder dots, so the
 * indicator reads as a premium PIN field. Each filled dot animates its scale and color in,
 * and the whole row tints to the error color on a failed entry.
 */
@Composable
private fun PinDots(count: Int, isError: Boolean, modifier: Modifier = Modifier) {
    val slots = maxOf(count, BASELINE_PIN_DOTS)
    val accent = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    Row(
        modifier = modifier.height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(slots) { index ->
            val filled = index < count
            val scale by animateFloatAsState(
                targetValue = if (filled) 1f else 0.55f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "pinDotScale",
            )
            val dotColor by animateColorAsState(
                targetValue = when {
                    filled && isError -> errorColor
                    filled -> accent
                    else -> GlassFill
                },
                label = "pinDotColor",
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}

/**
 * 3x4 numeric keypad: digits 1–9, then a blank slot, 0, and a backspace key. Disabled in
 * its entirety while a lockout is active (Req 2.4).
 */
@Composable
private fun NumericKeypad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(280.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                row.forEach { label ->
                    KeypadButton(
                        label = label,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { onDigit(label[0]) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Empty spacer keeps "0" centered under the keypad.
            Spacer(Modifier.weight(1f))
            KeypadButton(
                label = "0",
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onDigit('0') },
            )
            KeypadButton(
                label = "\u232B", // erase-to-the-left symbol
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onBackspace,
            )
        }
    }
}

/**
 * A single glassy, circular keypad key with a springy press animation. Uses the reusable
 * [glass] treatment so the keys match the rest of the premium surface language.
 */
@Composable
private fun KeypadButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "keyScale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) MaterialTheme.colorScheme.primary else GlassBorder,
        label = "keyBorder",
    )
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .scale(scale)
            .glass(shape = CircleShape, borderColor = borderColor, sheen = true)
            .clickableKey(enabled, interactionSource, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
        )
    }
}

/** Clickable wired to a shared [interactionSource] so the press animation can react to taps. */
private fun Modifier.clickableKey(
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.then(
    androidx.compose.foundation.clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    ),
)
