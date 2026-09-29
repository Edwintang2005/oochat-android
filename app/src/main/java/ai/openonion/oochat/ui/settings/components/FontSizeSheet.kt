package ai.openonion.oochat.ui.settings.components

import ai.openonion.oochat.ui.common.FontSizeOption
import ai.openonion.oochat.ui.components.AssistantAvatar
import ai.openonion.oochat.ui.theme.bubbleShape
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bottom sheet with a live-preview card + slider for choosing the app's font size.
 *
 * Preview mirrors the Figma design's `FontSizePage`: a header strip with the
 * agent avatar + name, then a mock assistant message bubble and a mock user
 * message bubble — all rendered at the currently-selected preview size, so
 * the user can see how their messages will look across both bubble variants
 * (the asymmetry matters: Figma tunes the corner radii and tail orientation
 * per sender). Labels and preview sizes come from the single shared
 * [FontSizeOption] table, also used by
 * [ai.openonion.oochat.ui.chat.ChatScreen]'s `fontScale` lookup — no
 * more manual sync between the two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FontSizeSheet(
    initialIndex: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var index by remember { mutableFloatStateOf(initialIndex.toFloat()) }
    val currentIndex = index.toInt().coerceIn(0, FontSizeOption.entries.lastIndex)
    val currentOption = FontSizeOption.entries[currentIndex]

    SettingsSheetScaffold(title = "Font size", onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.spacing.lg)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    AssistantAvatar(size = 28.dp, iconSize = 12.dp)
                    Text(
                        text = "Agent",
                        // The sender label the real chat bubble uses.
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

                // Agent bubble — mirrors TextBubbleContent's isUser=false
                // branch: surfaceContainer fill, no border. Hand-synced, since
                // importing from ui/chat would couple this sheet to it.
                Text(
                    text = "Quantum entanglement is when two particles share a correlated fate — the moment you observe one, the other is instantly determined.",
                    fontSize = currentOption.previewSize,
                    lineHeight = (currentOption.previewSize.value * 1.55f).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Reuses MessageBubble's real bubbleShape (corner +
                        // 4dp tail) instead of hardcoding different corner
                        // values, so this preview never drifts from what chat
                        // actually renders.
                        .clip(bubbleShape(isUser = false))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

                // User bubble — TextBubbleContent's isUser=true branch:
                // outlined, no fill, onSurface text. The fill goes to the
                // agent's reply because that's the tier carrying new content.
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "That's fascinating!",
                        fontSize = currentOption.previewSize,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clip(bubbleShape(isUser = true))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, bubbleShape(isUser = true))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Field label + its current value, same level as FieldLabel.
            Text(
                text = "Text size",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = currentOption.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

        // material3 1.4 restyled the default slider: a tall bar thumb, a gap
        // punched either side of it, and a stop indicator at the far end. On a
        // four-step type picker that reads as three competing marks on one
        // line. Kept the round thumb and a continuous track — the control says
        // "pick one of four", and nothing else needs to be said.
        val interactionSource = remember { MutableInteractionSource() }
        Slider(
            value = index,
            onValueChange = { index = it },
            valueRange = 0f..(FontSizeOption.entries.size - 1).toFloat(),
            steps = FontSizeOption.entries.size - 2,
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    thumbSize = DpSize(20.dp, 20.dp)
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    // 1.4 tints the unplayed track with a light green; against
                    // this sheet's own green surface the two nearly merge, which
                    // is the opposite of what a track is for.
                    colors = SliderDefaults.colors(
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.height(4.dp),
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                    drawStopIndicator = null
                )
            }
        )
        // Inset by the Slider thumb's radius on each side: the thumb's travel
        // stops that far short of the track ends, so full-width labels would
        // sit outboard of the positions they name.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FontSizeOption.entries.forEachIndexed { labelIndex, option ->
                val isCurrent = labelIndex == currentIndex
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))
        Button(
            onClick = { onConfirm(currentIndex); onDismiss() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Done")
        }
    }
}
