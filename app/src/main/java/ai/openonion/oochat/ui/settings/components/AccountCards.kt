package ai.openonion.oochat.ui.settings.components

import ai.openonion.oochat.ui.common.rememberClipboard
import ai.openonion.oochat.ui.theme.sectionLabel
import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.truncateMiddle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Warning shown when [ai.openonion.oochat.data.local.SafePreferencesWrapper]
 * fell back to plaintext storage because the Android Keystore-backed encrypted
 * prefs failed to initialize (e.g. a corrupted keystore after an OS restore).
 * Per product decision: warn, don't block — the app keeps working, just
 * without encryption at rest.
 *
 * [identityAffected] distinguishes the two cases, which are not equally
 * serious. API keys and connection config in plaintext are recoverable by
 * rotating them. The Ed25519 private key and the BIP39 recovery phrase in
 * plaintext are the identity itself, so that warning names what is exposed and
 * cannot be dismissed — there is nothing for the user to have "acknowledged"
 * while the phrase is still sitting on disk in readable words.
 */
@Composable
internal fun InsecureStorageWarningBanner(identityAffected: Boolean, onDismiss: (() -> Unit)?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.sm),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(MaterialTheme.spacing.lg)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.spacing.sm)
            ) {
                Text(
                    text = if (identityAffected) {
                        "Your recovery phrase is not encrypted"
                    } else {
                        "Secure storage unavailable"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (identityAffected) {
                        "This device could not initialize encrypted storage, so this account's " +
                            "private key and 12-word recovery phrase are stored in readable form. " +
                            "Anyone who can reach this app's data can take over the account. " +
                            "Back up the phrase, then reset your identity once encrypted storage works again."
                    } else {
                        "This device could not initialize encrypted storage, so saved API keys " +
                            "and connection settings are stored without encryption."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.xs2)
                )
            }
            // Absent for the identity case: dismissing is an acknowledgement,
            // and there is nothing to acknowledge while the phrase is still on
            // disk in plaintext. No explicit size on the button — IconButton's
            // own 48dp touch target (WCAG 2.5.5 / Material minimum) applies,
            // and the 20dp Icon keeps the glyph visually unchanged.
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Account section: Balance card + Identity card, matching the Figma design's
 * "Manage your identity and credits" block. Backup/Import/Reset use real
 * BIP39-backed identity management. Credits are an agent-scoped concept —
 * each agent reports its own balance over AGENT_PROFILE — so no
 * account-level balance is featured here, mirroring the reference web
 * client's settings page.
 */
@Composable
internal fun AccountSection(
    walletAddress: String,
    apiKey: String?,
    onBackupSeed: () -> Unit,
    onImportKey: () -> Unit,
    onReset: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.lg)) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = MaterialTheme.spacing.sm)) {
            Text(
                text = "ACCOUNT",
                style = MaterialTheme.typography.sectionLabel,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "Manage this device's identity",
                // Supporting text under a heading — bodySmall, like every
                // other subtitle/helper line in Settings.
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        IdentityCard(
            walletAddress = walletAddress,
            apiKey = apiKey,
            onBackupSeed = onBackupSeed,
            onImportKey = onImportKey,
            onReset = onReset
        )
    }
    // Wider than the gap between settings groups (`xl`), which is wider than
    // the gap inside this block (`sm`) — that's what makes it a separate tier.
    Spacer(modifier = Modifier.height(MaterialTheme.spacing.xxl))
}

/** Wallet address + API key + identity-management actions. */
@Composable
internal fun IdentityCard(
    walletAddress: String,
    apiKey: String?,
    onBackupSeed: () -> Unit,
    onImportKey: () -> Unit,
    onReset: () -> Unit
) {
    val copyToClipboard = rememberClipboard()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
    ) {
        IdentityRow(
            label = "WALLET ADDRESS",
            value = walletAddress.truncateMiddle(12, 6),
            onCopy = { copyToClipboard("Wallet address", walletAddress) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        IdentityRow(
            label = "API KEY",
            value = apiKey?.truncateMiddle(12, 6) ?: "Not authenticated",
            onCopy = apiKey?.let { key -> { copyToClipboard("API key", key) } }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        IdentityActionRow(
            icon = Icons.Default.VpnKey,
            label = "Back up seed phrase",
            onClick = onBackupSeed
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        IdentityActionRow(
            icon = Icons.AutoMirrored.Filled.Login,
            label = "Import Recovery Key",
            onClick = onImportKey
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        IdentityActionRow(
            icon = Icons.Default.RestartAlt,
            label = "Reset Identity",
            onClick = onReset,
            danger = true
        )
    }
}

@Composable
internal fun IdentityRow(label: String, value: String, onCopy: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A two-line list item, 64dp. The Copy button's invisible 48dp touch
            // target used to sit *under* the value and stretch the row to ~94dp
            // — twice the action rows below it, with the extra height landing as
            // dead space. Centred beside the text, it fits inside the row.
            .heightIn(min = 64.dp)
            .padding(horizontal = MaterialTheme.spacing.md2, vertical = MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.sectionLabel,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = value,
                // This is the row's content, not a caption under it, so it sits
                // at body level rather than bodySmall. No tracking: bodySmall's
                // 0.4sp on top of a monospace advance read as stretched.
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (onCopy != null) {
            val copyState = rememberCopyState()
            OutlinedButton(
                onClick = {
                    onCopy()
                    copyState.markCopied()
                },
                // Visual stays 28dp (it would dominate the row otherwise);
                // minimumInteractiveComponentSize() pads the touch/layout
                // bounds out to 48dp invisibly around it.
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .height(28.dp),
                contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.sm),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(
                    1.dp,
                    if (copyState.copied) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (copyState.copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            ) {
                // Button level, same as this section's "Add credits".
                Text(if (copyState.copied) "Copied" else "Copy", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun IdentityActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    val contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            // Same 14dp text inset as IdentityRow so the card has one left edge.
            .padding(horizontal = MaterialTheme.spacing.md2, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm2)
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        // Same level as SettingsRow/SwitchRow's label: a tappable settings row.
        Text(label, style = MaterialTheme.typography.bodyLarge, color = contentColor, modifier = Modifier.weight(1f))
        if (!danger) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
