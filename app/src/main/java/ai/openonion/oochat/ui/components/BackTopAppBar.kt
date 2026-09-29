package ai.openonion.oochat.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A [TopAppBar] with a back-arrow navigation icon: the title/back-button/
 * actions shell repeated (with only the title, actions, and occasional
 * color overrides differing) across the various single-purpose screens.
 *
 * Uses [Icons.AutoMirrored.Filled.ArrowBack] rather than the deprecated
 * `Icons.Default.ArrowBack` — visually identical in LTR, mirrored correctly
 * for RTL.
 *
 * @param backIconTint Defaults to the icon's normal content color; some
 *   screens override it to dim the arrow.
 * @param colors Null uses TopAppBarDefaults.topAppBarColors() unmodified.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopAppBar(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    colors: TopAppBarColors? = null,
    backIconTint: Color? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = backIconTint ?: LocalContentColor.current
                )
            }
        },
        actions = actions,
        colors = colors ?: TopAppBarDefaults.topAppBarColors(),
        modifier = modifier
    )
}
