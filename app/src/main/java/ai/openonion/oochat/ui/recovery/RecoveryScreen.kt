package ai.openonion.oochat.ui.recovery

import ai.openonion.oochat.ui.theme.ButtonToken
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

// "below" would be wrong now that the saved config sits behind a "View saved
// config" toggle rather than in a card under this message.
private const val DEFAULT_ERROR_MESSAGE =
    "Unable to connect to the agent. Check your network and the saved configuration, then retry."

/**
 * Recovery screen shown when automatic reconnection fails.
 *
 * Displays error information and provides recovery options. The actual
 * retry attempt (with its progress UI) happens on the shared
 * [ai.openonion.oochat.ui.loading.LoadingScreen] — the same screen
 * auto-login and Onboarding use — reached via [onRetry], instead of a third
 * duplicate connecting UI living inside Recovery itself.
 *
 * @param errorMessage The real failure reason from the LoadingScreen connection
 *   attempt (passed through navigation args). Falls back to a generic message
 *   if absent (e.g. a future caller that doesn't have a specific reason).
 * @param onRetry Called when the user taps "Retry connection"
 * @param onEditConfiguration Called when user wants to edit configuration
 */
@Composable
fun RecoveryScreen(
    errorMessage: String? = null,
    onRetry: () -> Unit,
    onEditConfiguration: () -> Unit,
    viewModel: RecoveryViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val config by viewModel.config.collectAsState()
    var configExpanded by remember { mutableStateOf(false) }

    Box(
        // No Scaffold here, so the screen takes its own insets now that the app
        // draws edge-to-edge — without this the Retry button sits under the
        // gesture bar.
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xl),
            modifier = Modifier
                .padding(MaterialTheme.spacing.xxl)
                .fillMaxWidth()
        ) {
            // Error icon — boxed in a bordered rounded-square, matching the
            // icon-box language used elsewhere (e.g. LoadingScreen's brand
            // icon, AgentListScreen's empty-state icon) instead of a bare icon.
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Connection Error",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = "Connection Failed",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Inline, not a card. This screen had six stacked blocks at roughly
            // equal weight; a card here would tie the error to the Retry button.
            Text(
                text = errorMessage ?: DEFAULT_ERROR_MESSAGE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // Collapsed: reference material for when the user suspects the
            // config is wrong, not something needed on the way to Retry.
            AnimatedVisibility(visible = config != null, enter = fadeIn()) {
                val savedConfig = config
                if (savedConfig != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(
                            onClick = { configExpanded = !configExpanded },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Text(
                                text = if (configExpanded) "Hide saved config" else "View saved config",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(start = MaterialTheme.spacing.xs)
                                    .size(16.dp)
                                    .graphicsLayer { rotationZ = if (configExpanded) 180f else 0f }
                            )
                        }
                        AnimatedVisibility(visible = configExpanded, enter = fadeIn()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Server: ${savedConfig.serverUrl}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                savedConfig.agentAddress?.let { address ->
                                    Text(
                                        text = "Agent: $address",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Retry button — navigates to the shared LoadingScreen, which
            // reads the saved config and performs the actual attempt. The one
            // filled button here, and the focal element.
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ButtonToken.FullWidth),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
                Text("Retry connection")
            }

            // Text, not outlined — see LoadingScreen's Cancel for the same call.
            TextButton(
                onClick = onEditConfiguration,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Edit configuration")
            }

            // Help text
            Text(
                text = "If the problem persists, please check your network connection or contact support.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
