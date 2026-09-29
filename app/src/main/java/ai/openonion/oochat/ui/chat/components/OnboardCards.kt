package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * How long "Verifying…" may run before the card gives the field back. A
 * healthy ONBOARD_SUBMIT → ONBOARD_SUCCESS round trip is ~200 ms, so silence
 * past this is a lost frame, not a slow one.
 */
internal const val ONBOARD_VERIFY_TIMEOUT_MS = 8_000L

/** Verifying ended in silence — nothing came back either way. */
internal const val ONBOARD_NO_RESPONSE = "No response — try again."

/** The socket died while verifying, so the verdict can never arrive. */
internal const val ONBOARD_CONNECTION_LOST = "Connection lost — reconnect and try again."

/**
 * Onboarding gate card — shared by both the initial "Onboarding Required"
 * prompt and the "Onboarding Rejected" retry (which only adds [errorReason]).
 * Was two ~130-line near-duplicate composables; consolidated into one
 * parameterized by title/subtitle/errorReason.
 *
 * Payment method is intentionally unreachable from the UI — the wire
 * protocol carries [ChatItem.OnboardRequired.paymentAmount]/paymentAddress,
 * but this app's onboarding flow only ever exercises the invite-code path.
 */
@Composable
internal fun OnboardGateCard(
    title: String,
    subtitle: String,
    onOnboard: (method: String, inviteCode: String?, payment: Double?) -> Unit,
    errorReason: String? = null,
    // Deliberately hasLiveConnection(), not a strict Connected check — the
    // server withholds CONNECTED until onboarding clears, so a strict check
    // would leave `submitted` stuck true forever with no exit from
    // "Verifying…" once the connection that carried the submission drops.
    hasLiveConnection: Boolean = true
) {
    // Saveable, not remember: the typed code has to outlive the row scrolling
    // out of the LazyColumn (and a rotation) or a retry means retyping a
    // password-masked field.
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var showInviteCode by rememberSaveable { mutableStateOf(false) }
    // Verdict for the two outcomes the server never reports — silence and a
    // dead socket. Superseded by any real server reason.
    var localError by rememberSaveable { mutableStateOf<String?>(null) }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // One submit path for both the button and the IME Done key. inviteCode is
    // deliberately not cleared here — a local failure (timeout, dead socket)
    // lands back on this field with the code still in it. Only a server
    // rejection empties it (see the errorReason effect below).
    val submit = {
        if (inviteCode.isNotBlank()) {
            focusManager.clearFocus()
            keyboard?.hide()
            localError = null
            // Submitting with no socket used to leave the button visibly dead.
            if (hasLiveConnection) submitted = true else localError = ONBOARD_CONNECTION_LOST
            onOnboard("invite_code", inviteCode.trim(), null)
        }
    }

    // Three ways out of "Verifying…"; only the first used to exist, so the
    // other two spun forever with no way back to the input. A dropped socket
    // is called out separately from silence — they need different fixes.
    LaunchedEffect(submitted, hasLiveConnection) {
        if (!submitted) return@LaunchedEffect
        if (!hasLiveConnection) {
            submitted = false
            localError = ONBOARD_CONNECTION_LOST
            return@LaunchedEffect
        }
        delay(ONBOARD_VERIFY_TIMEOUT_MS)
        submitted = false
        localError = ONBOARD_NO_RESPONSE
    }

    // One card spans the whole gate now (required → failed → required), so the
    // rejected code has to be cleared here — nothing else rebuilds the field,
    // and the server has already said that code is wrong. Keyed on the reason
    // so it fires at that transition only: clearing on every recomposition
    // would make the retry field impossible to type into.
    LaunchedEffect(errorReason) {
        if (errorReason != null) {
            keyboard?.hide()
            inviteCode = ""
            // A real verdict ends the spinner and outranks the local guess.
            submitted = false
            localError = null
        }
    }

    val displayedError = localError ?: errorReason

    // Figma tints tertiary directly into surface-container rather than using
    // the tertiaryContainer role, with alpha bumped above Figma's literal
    // 8-16% since that reads as washed-out gray once actually rendered.
    val tertiary = MaterialTheme.colorScheme.tertiary
    // surfaceContainerHigh, like the other decision gates — this card blocks
    // the conversation until it's answered.
    val cardBackground = lerp(MaterialTheme.colorScheme.surfaceContainerHigh, tertiary, 0.16f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        // Explicit `large`: without it this card fell back to the theme's 12dp
        // default while the three cards beside it were 16dp.
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        border = BorderStroke(1.dp, tertiary.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md2),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm2)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(tertiary.copy(alpha = 0.28f))
                        .border(BorderStroke(1.dp, tertiary.copy(alpha = 0.5f)), MaterialTheme.shapes.small),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = tertiary
                    )
                }
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Inline reason — a server rejection, or a local timeout/dropped
            // socket. Uses errorContainer so it reads as a real error
            // indicator in both light and dark mode (error on surface alone
            // washes out on dark surfaces).
            if (displayedError != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(MaterialTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = displayedError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (submitted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    StatusIcon(
                        status = IndicatorStatus.RUNNING,
                        runningSize = 16.dp,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Verifying…",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)) {
                    OutlinedTextField(
                        value = inviteCode,
                        onValueChange = { inviteCode = it },
                        placeholder = { Text("Enter your invite code") },
                        singleLine = true,
                        visualTransformation = if (showInviteCode) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        trailingIcon = {
                            IconButton(onClick = { showInviteCode = !showInviteCode }) {
                                Icon(
                                    imageVector = if (showInviteCode) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                    contentDescription = if (showInviteCode) "Hide invite code" else "Show invite code",
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tertiary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            cursorColor = tertiary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = submit,
                        enabled = inviteCode.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.outline
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Redeem code")
                    }
                }
            }
        }
    }
}

/** Small emerald confirmation pill after the user clears the gate. */
@Composable
internal fun OnboardSuccessCard(item: ChatItem.OnboardSuccess) {
    // Figma has no secondary-container role here — it's a translucent
    // primary tint (10% background, 30% border).
    val primary = MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = lerp(MaterialTheme.colorScheme.surface, primary, 0.10f)
        ),
        border = BorderStroke(1.dp, primary.copy(alpha = 0.30f))
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.sm2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Verified — ${item.message.ifBlank { "Continuing your request" }}",
                style = MaterialTheme.typography.labelMedium,
                color = primary
            )
        }
    }
}
