package ai.openonion.oochat.ui.settings.components

import ai.openonion.oochat.domain.model.ThemeMode
import ai.openonion.oochat.ui.theme.sectionLabel
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * A settings group: a quiet ALL-CAPS heading over a run of bare rows.
 *
 * Deliberately not a [Card]. Eight carded groups gave every group the same
 * weight as the account block, so the page read as a uniform table. Grouping is
 * carried by the gap between sections (`xl`) being wider than the rows' own
 * padding, with [SectionDivider] separating rows inside one.
 *
 * The account block ([AccountSection]) is the only container left on this
 * screen by design — a container now means "this is the important one".
 */
@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(bottom = MaterialTheme.spacing.xl)) {
        Text(
            text = title.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.sectionLabel,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(
                start = MaterialTheme.spacing.lg,
                end = MaterialTheme.spacing.lg,
                bottom = MaterialTheme.spacing.xs2
            )
        )
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.lg),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
internal fun SettingsRow(
    label: String,
    value: String? = null,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Tighter than the gap between sections so groups read as groups;
            // heightIn holds the 48dp touch target for single-line rows.
            .heightIn(min = 48.dp)
            .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.sm2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.lg))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(end = MaterialTheme.spacing.xs)
            )
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * Static disclosure text closing out a section — no icon, no divider, no touch
 * target, so it reads as a note rather than as another tappable row.
 */
@Composable
internal fun SectionFootnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = MaterialTheme.spacing.lg,
            end = MaterialTheme.spacing.lg,
            top = MaterialTheme.spacing.sm2
        )
    )
}

@Composable
internal fun SwitchRow(
    label: String,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.sm2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor ?: MaterialTheme.colorScheme.outline
                )
            }
        }
        // 1.4 lightened the unchecked switch: border outline -> outlineVariant
        // and the thumb off outline entirely. Held where it was — the off state
        // has to stay legible against surfaceContainer, not fade into it.
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
internal fun ThemeModeRow(current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        ThemeMode.entries.forEach { mode ->
            val selected = mode == current
            val label = when (mode) {
                ThemeMode.LIGHT -> "Light"
                ThemeMode.DARK -> "Dark"
                ThemeMode.SYSTEM -> "System"
            }
            val icon = when (mode) {
                ThemeMode.LIGHT -> Icons.Default.LightMode
                ThemeMode.DARK -> Icons.Default.DarkMode
                ThemeMode.SYSTEM -> Icons.Default.PhoneAndroid
            }
            // OutlinedButton is itself an accessibility merge boundary
            // (Role.Button, no `selected`), so a plain merging ancestor
            // stands next to it rather than absorbing it — TalkBack would
            // see two stops. clearAndSetSemantics replaces the button's own
            // node outright with the one RadioButton node this option needs,
            // re-declaring the click action clearAndSetSemantics discards.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        contentDescription = label
                        role = Role.RadioButton
                        this.selected = selected
                        onClick(label = null) {
                            onChange(mode)
                            true
                        }
                    }
            ) {
                OutlinedButton(
                    onClick = { onChange(mode) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    border = if (selected) {
                        BorderStroke(width = 1.5.dp, color = MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                    contentPadding = PaddingValues(horizontal = MaterialTheme.spacing.sm, vertical = MaterialTheme.spacing.xs),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.xxs))
                    // labelLarge, like every other button/toggle label in the app.
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
