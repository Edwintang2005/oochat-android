package ai.openonion.oochat.ui.settings.components

import ai.openonion.oochat.ui.components.ConfirmationSheet
import ai.openonion.oochat.ui.theme.ButtonToken
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Shows the identity's recovery phrase behind a reveal gate, matching
 * Figma's `BackupSeedPage`: warning card → "Tap to reveal" → 3-column word
 * grid → copy → "I've Stored It Safely" confirmation. Falls back to the
 * legacy raw hex key (with its own notice) for identities that predate the
 * mnemonic feature or were imported via hex — see [KeyManager.BackupExport].
 *
 * Uses [LocalClipboardManager] directly (rather than the
 * [ai.openonion.oochat.ui.common.rememberClipboard] write-only
 * helper other copy sites in this package use) because the copied secret
 * (mnemonic/private key) is auto-wiped from the clipboard after 30 seconds
 * — see [clipboardSecret] below — which requires reading the clipboard back
 * to confirm nothing else overwrote it first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupSeedSheet(export: ai.openonion.oochat.crypto.KeyManager.BackupExport, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var revealed by remember { mutableStateOf(false) }
    val copyState = rememberCopyState()
    var confirmed by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    // Tracks which secret is currently on the clipboard so it can be wiped
    // after a timeout instead of sitting there indefinitely — a raw private
    // key/mnemonic left on the clipboard is readable by any other app with
    // clipboard access until something else overwrites it.
    var clipboardSecret by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(clipboardSecret) {
        val secret = clipboardSecret ?: return@LaunchedEffect
        delay(30_000)
        // Only clear if the clipboard still holds exactly what we put there —
        // don't clobber something the user copied from elsewhere afterward.
        if (clipboard.getText()?.text == secret) {
            clipboard.setText(AnnotatedString(""))
        }
        clipboardSecret = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // 1.4 draws the handle at full onSurfaceVariant. It is a grab affordance,
        // not content — kept at the muted weight it had, so it reads as chrome.
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.md)
        ) {
            if (confirmed) {
                PhraseSecuredConfirmation()
                return@Column
            }

            when (export) {
                is ai.openonion.oochat.crypto.KeyManager.BackupExport.Phrase -> {
                    SeedWarningCard()
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

                    if (!revealed) {
                        // Keeps its border: that outline is the masked placeholder
                        // for the phrase itself, not button chrome.
                        OutlinedButton(
                            onClick = { revealed = true },
                            modifier = Modifier.fillMaxWidth().height(ButtonToken.FullWidth),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            // material3 1.4 moved OutlinedButton's label from
                            // primary to onSurfaceVariant. Held at primary here:
                            // green is what marks this as the deliberate reveal
                            // of a secret, not just another row action.
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Tap to reveal phrase", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        MnemonicWordGrid(words = export.mnemonic.trim().split(Regex("\\s+")))
                        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm2))

                        // Utility action beside the filled "I've Stored It
                        // Safely" that actually closes this sheet.
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(export.mnemonic))
                                copyState.markCopied()
                                clipboardSecret = export.mnemonic
                            },
                            modifier = Modifier.fillMaxWidth().height(ButtonToken.Compact),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(if (copyState.copied) "Copied!" else "Copy Phrase", fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

                        Button(
                            onClick = { confirmed = true },
                            modifier = Modifier.fillMaxWidth().height(ButtonToken.FullWidth),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("I've Stored It Safely", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                is ai.openonion.oochat.crypto.KeyManager.BackupExport.LegacyHexKey -> {
                    LegacyHexBackup(
                        privateKeyHex = export.privateKeyHex,
                        copied = copyState.copied,
                        onCopy = {
                            clipboard.setText(AnnotatedString(export.privateKeyHex))
                            copyState.markCopied()
                            clipboardSecret = export.privateKeyHex
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))
        }
    }
}

/** Success state after the user confirms they stored the phrase. */
@Composable
private fun PhraseSecuredConfirmation() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.xxl)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
        Text(
            "Phrase secured",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
        Text(
            "Your recovery phrase has been noted. Keep it offline and never share it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
    }
}

/** Red warning banner shown above the reveal gate / word grid. */
@Composable
private fun SeedWarningCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f), MaterialTheme.shapes.medium)
            .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm2)
    ) {
        Text(
            "Ed25519 Recovery Seed",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            "These 12 words are the only way to recover your identity and assets. Write them down offline. Never share them with anyone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = MaterialTheme.spacing.xs)
        )
    }
}

/** Numbered 3-column grid of the revealed mnemonic words. */
@Composable
private fun MnemonicWordGrid(words: List<String>) {
    val rows = words.chunked(3)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(MaterialTheme.spacing.md)
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                row.forEachIndexed { colIndex, word ->
                    // Positional index — words.indexOf(word) previously
                    // mis-numbered phrases containing a repeated word.
                    val index = rowIndex * 3 + colIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = word,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
            if (rowIndex != rows.lastIndex) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
            }
        }
    }
}

/** Fallback for identities that predate mnemonics: raw hex key + copy. */
@Composable
private fun LegacyHexBackup(privateKeyHex: String, copied: Boolean, onCopy: () -> Unit) {
    Text(
        "Legacy Identity",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
    Text(
        "This identity was created before recovery phrases were supported, so there's no 12-word phrase for it — only the raw private key below.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
    Text(
        text = privateKeyHex,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(MaterialTheme.spacing.md)
    )
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
    Button(
        onClick = onCopy,
        modifier = Modifier.fillMaxWidth().height(ButtonToken.FullWidth),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(if (copied) "Copied!" else "Copy Private Key", fontWeight = FontWeight.Bold)
    }
}

/**
 * Bottom sheet accepting a 12/24-word recovery phrase OR a raw hex private
 * key to restore/switch identity — matches Figma's Import Key sheet
 * (mnemonic-only there; Android's [KeyManager.importFromPhraseOrHex] also
 * accepts the hex form, same as the onboarding flow's import already does).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportKeySheet(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val wordCount = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
    val looksValid = wordCount == 12 || wordCount == 24 || input.trim().removePrefix("0x").length == 128

    ConfirmationSheet(
        title = "Import Key",
        subtitle = "Enter your 12-word recovery phrase, or a raw 64-byte hex private key, to restore an Ed25519 identity.",
        subtitleSpacingBefore = MaterialTheme.spacing.xs,
        onDismiss = onDismiss,
        content = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; error = null },
                // The placeholder slot does NOT inherit `textStyle` — without
                // this it renders at LocalTextStyle (bodyLarge, 16sp) and the
                // text visibly shrinks to 12sp the moment you start typing.
                placeholder = {
                    Text(
                        "word1 word2 word3 … word12",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                isError = error != null,
                supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
        },
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(ButtonToken.FullWidth),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        try {
                            onImport(input)
                        } catch (e: Exception) {
                            error = e.message ?: "Invalid recovery phrase or key"
                        }
                    },
                    enabled = looksValid,
                    modifier = Modifier.weight(1f).height(ButtonToken.FullWidth),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Import Now", fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

/**
 * Destructive confirmation before overwriting the current identity with a
 * brand-new one, matching Figma's Reset Identity sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResetIdentitySheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmationSheet(
        title = "Reset Identity?",
        titleColor = MaterialTheme.colorScheme.error,
        centered = true,
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                    .border(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.30f), MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        body = "This generates a new Ed25519 key pair and permanently destroys your current identity. Wallet balance and agent connections will be lost. This cannot be undone.",
        onDismiss = onDismiss,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(ButtonToken.FullWidth),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f).height(ButtonToken.FullWidth),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Reset Identity", fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}
