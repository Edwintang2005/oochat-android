package ai.openonion.oochat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single-line bordered pill text field: fixed-height box, hand-drawn
 * placeholder, border that turns primary on focus, optional leading/trailing
 * slots.
 *
 * Material3's OutlinedTextField was tried first for these fields, but its
 * floating label reserves extra height that varies with focus state, and its
 * `placeholder` slot doesn't reliably stay single-line — both of which broke
 * pixel-alignment with adjacent buttons. A plain [BasicTextField] in a fixed
 * `heightIn(min = 52.dp)` box sidesteps both problems.
 *
 * Text renders at `bodyMedium` with no per-call-site size knob: the Server
 * URL / Agent Address / API Key fields are one input level, and the old
 * `fontSize` parameter had let the discovery surfaces drift to 13sp while
 * the Edit Agent form stayed at 14sp.
 */
@Composable
fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /**
     * Accessible name for the field, announced together with its value —
     * pass the same string the sibling label composable displays. Null (the
     * default) leaves the field unnamed for call sites with no such label.
     */
    label: String? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    monospace: Boolean = false,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    unfocusedBorderColor: Color = MaterialTheme.colorScheme.outline,
    itemSpacing: Dp = 0.dp,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    anchorModifier: Modifier = Modifier,
    /** Fires on the inner field's own focus, not the pill's — see [onFocusChanged]. */
    onFocusChanged: (Boolean) -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else unfocusedBorderColor
    val fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(backgroundColor)
            .border(BorderStroke(1.5.dp, borderColor), shape)
            .then(anchorModifier)
            .padding(horizontal = 14.dp)
    ) {
        leadingContent?.invoke()
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = fontFamily
                ),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        if (label != null) {
                            contentDescription = label
                        }
                    }
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChanged(it.isFocused)
                    }
            )
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = fontFamily
                    ),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailingContent?.invoke()
    }
}
