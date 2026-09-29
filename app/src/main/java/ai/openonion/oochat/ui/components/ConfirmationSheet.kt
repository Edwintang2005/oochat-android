package ai.openonion.oochat.ui.components

import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dedups the ModalBottomSheet/Column/spacer boilerplate; knobs default to
 * the most common values seen across call sites.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmationSheet(
    title: String,
    onDismiss: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    centered: Boolean = false,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitle: String? = null,
    subtitleSpacingBefore: Dp = 0.dp,
    body: String? = null,
    titleToBodySpacing: Dp = MaterialTheme.spacing.sm,
    columnVerticalPadding: Dp = MaterialTheme.spacing.md,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    contentSpacingBefore: Dp = MaterialTheme.spacing.md,
    actions: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

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
                .padding(horizontal = MaterialTheme.spacing.lg, vertical = columnVerticalPadding),
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
        ) {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start
            )
            subtitle?.let {
                Spacer(modifier = Modifier.height(subtitleSpacingBefore))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            body?.let {
                Spacer(modifier = Modifier.height(titleToBodySpacing))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start
                )
            }
            if (content != null) {
                Spacer(modifier = Modifier.height(contentSpacingBefore))
                content()
            }
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))
            actions()
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))
        }
    }
}

@Composable
internal fun DangerConfirmActions(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "Cancel",
    confirmBorder: Boolean = false
) {
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        // Text, not outlined: an outline weighs too close to the filled confirm
        // beside it, and a destructive sheet needs those two to differ.
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = MaterialTheme.spacing.xs)
        ) {
            Text(cancelLabel)
        }
        Button(
            onClick = onConfirm,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.error
            ),
            border = if (confirmBorder) {
                BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
            } else {
                null
            },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = MaterialTheme.spacing.xs)
        ) {
            Text(confirmLabel)
        }
    }
}
