package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.spacing
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Below one dollar an agent is a turn or two from refusing, close enough that
 * the reader should hear about it before it happens. Above it, silence — a
 * balance nobody needs to act on is clutter. The threshold is the reference web
 * client's `isLowBalance`, kept identical so the two clients warn together.
 */
internal fun isLowBalance(balanceUsd: Double): Boolean = balanceUsd < 1.0

/**
 * Anyone can pay for any address — checkout takes an address and no auth. The
 * purchase page reads it from `?key=`; any other parameter name lands the payer
 * on an empty form with the address to paste themselves.
 */
internal fun agentPurchaseUrl(address: String): String =
    "https://o.openonion.ai/purchase?key=$address"

/**
 * The agent is about to stop answering, said where the user is about to type.
 *
 * Sits with the composer rather than in the drawer's account card, which is
 * where the balance used to live as a permanent read-only row: now that it only
 * appears when the balance is low, a warning behind a drawer the user has to
 * open is one they would see after the failure rather than before it. This is
 * the same call the web client makes for its own low-balance notice.
 *
 * Renders nothing unless the agent published a balance, that balance is low,
 * and there is an address to address a top-up to.
 *
 * @param balanceUsd The agent's own operating balance, from its `AGENT_PROFILE`
 *   — null for the agents that publish none, and before any connection.
 * @param agentAddress The agent's public key, which the purchase page needs.
 */
@Composable
internal fun LowBalanceBar(
    balanceUsd: Double?,
    agentAddress: String?,
    modifier: Modifier = Modifier
) {
    if (balanceUsd == null || !isLowBalance(balanceUsd)) return
    val address = agentAddress?.takeIf { it.isNotBlank() } ?: return
    val context = LocalContext.current
    val error = MaterialTheme.colorScheme.error

    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The whole row is the control, and nothing opens a browser
                // except this tap — there is no automatic path to the intent.
                .heightIn(min = 48.dp)
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(agentPurchaseUrl(address)))
                    try {
                        context.startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        FileLogger.w(LogTags.ACCOUNT, "No browser to open the top-up page: ${e.message}")
                    }
                }
                .padding(horizontal = MaterialTheme.spacing.lg, vertical = MaterialTheme.spacing.xs2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs2)
        ) {
            Icon(
                imageVector = Icons.Filled.CreditCard,
                contentDescription = null,
                tint = error,
                modifier = Modifier.size(16.dp)
            )
            Text(
                // At zero the number is the least useful thing on the row —
                // "$0.00" reads as a balance like any other and leaves the
                // reader to work out what it implies. Say the implication.
                text = if (balanceUsd <= 0.0) {
                    "Out of credits — this agent will stop responding"
                } else {
                    "Running low — $" + "%.2f".format(balanceUsd) + " left"
                },
                style = MaterialTheme.typography.labelSmall,
                color = error,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Add credits",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
