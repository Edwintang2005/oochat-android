package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.ui.chat.components.ChatItemBubble
import ai.openonion.oochat.ui.chat.components.DateDivider
import ai.openonion.oochat.ui.theme.spacing
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Caps the chat column's width on tablets and unfolded foldables
 * instead of letting a line run edge-to-edge. chatBody is 15sp, and a
 * Manrope glyph there averages ~8dp, so 600dp of column holds roughly 70
 * characters once list/bubble padding is subtracted — the middle of the
 * 60-75 char comfortable-measure range. Any phone is narrower than this, so
 * widthIn is a no-op there. Shared with the composer, which has to line up
 * with the transcript above it or the column looks accidental.
 */
internal val ChatContentMaxWidth = 600.dp

@Composable
fun MessageList(
    items: List<ChatItem>,
    timestamps: Map<String, Long>,
    wasCleared: Boolean,
    onRespond: (String) -> Unit,
    onApprove: (itemId: String, approved: Boolean, scope: String, mode: String?, feedback: String?) -> Unit,
    onOnboard: (method: String, inviteCode: String?, payment: Double?) -> Unit,
    onPlanReviewResponse: (message: String) -> Unit,
    onUlwResponse: (action: String, turns: Int?, mode: String?) -> Unit,
    onGateResolved: (itemId: String) -> Unit = {},
    onRetry: (content: String, images: List<String>?, files: List<String>?) -> Unit = { _, _, _ -> },
    // Resends one of the user's own messages that never left the device. Keyed
    // by the bubble's id, not by "the last message", so it revives the message
    // the user actually tapped.
    onResendMessage: (String) -> Unit = {},
    renderMarkdown: Boolean = true,
    hasLiveConnection: Boolean = true,
    /**
     * Identifies the conversation on screen, so the scroll bookkeeping below
     * re-arms on a switch. Deliberately not the oldest message's id, which now
     * also changes when a page of older history is prepended — that read a
     * page-in as a whole new conversation and yanked the reader to the bottom.
     */
    conversationKey: String? = null,
    hasOlderMessages: Boolean = false,
    onLoadOlderMessages: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    // A tap the transcript's own children did not take is a tap on its
    // background, and that puts the composer's keyboard away. Hung on
    // consumption rather than a whitelist: every clickable and every text field
    // inside a card takes the down in the Main pass before this sees it, so
    // they all keep their taps for free, including cards added later. A drag is
    // a scroll and cancels the gesture — dismissing there would move the layout
    // out from under someone who scrolled up to check something mid-message.
    // Focus is cleared rather than the IME hidden: leaving the field focused
    // keeps the caret in it and holds the pill in its focused border.
    val transcript = modifier.pointerInput(Unit) {
        detectTapGestures { focusManager.clearFocus() }
    }
    // Keyed on the conversation, so switching arms it again; it stays put while
    // a conversation streams and while older history pages in above.
    var landedOnNewest by remember(conversationKey) { mutableStateOf(false) }
    // The id of the last trailing User row this list has already reacted to —
    // see the effect below. Same key as above, so a conversation switch arms it
    // again rather than mistaking the new chat's tail for an old send.
    var followedOwnSendId by remember(conversationKey) { mutableStateOf<String?>(null) }
    // Where the reader was when a page of older history was asked for, so the
    // prepend can put them back. Not left to LazyColumn's own key re-anchoring:
    // that only looks a bounded distance around the previous window, and a
    // whole page lands well outside it — the reader would silently end up a
    // page further back in the conversation.
    var prependAnchor by remember(conversationKey) { mutableStateOf<PrependAnchor?>(null) }
    // One label per item, non-null only where the day changes — computed here
    // so the divider can be emitted inside the same lazy item as its message.
    //
    // Both keys get a new identity on every arriving message, so this re-runs
    // for the whole list each time. The zone conversion is the expensive part
    // of it and is what the cache holds; the walk itself is then map lookups.
    // Cached here rather than baked in at persist time because the labels are
    // relative ("Today"/"Yesterday") and positional — a stored label goes stale
    // at midnight and again whenever a page of older history is prepended.
    val dayCache = remember { HashMap<Long, LocalDate>() }
    val dayLabels = remember(items, timestamps) { dayDividerLabels(items, timestamps, dayCache) }

    // Keyed on the whole list, not its size and last item: a reply is folded
    // into the Turn placeholder that was created ahead of the tool cards (see
    // ChatEventHelpers.mergeIntoPendingTurn), so the row that grows sits in the
    // middle and neither of those two keys moves. The list is rebuilt around
    // one replaced item, so the comparison is identity checks plus one.
    // Asked for before the top is actually reached: the restore below needs a
    // real message as its anchor, and at the very top the header row is all
    // there is. Re-armed by `hasOlderMessages`/`landedOnNewest` flipping —
    // starting it before the opening scroll-to-bottom would page in history
    // nobody asked for, since a list lays out at index 0.
    LaunchedEffect(listState, hasOlderMessages, landedOnNewest) {
        if (!hasOlderMessages || !landedOnNewest) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collect { first ->
            if (first > OLDER_PAGE_PREFETCH_INDEX) return@collect
            val anchorItem = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key != BEGINNING_ITEM_KEY }
            prependAnchor = (anchorItem?.key as? String)?.let {
                PrependAnchor(itemId = it, offset = anchorItem.offset, sizeBefore = items.size)
            }
            onLoadOlderMessages()
        }
    }

    LaunchedEffect(items) {
        // Put the reader back on the row they were looking at before the page
        // landed. Cleared unconditionally: a request that came back empty must
        // not leave an anchor to be applied to some later, unrelated change.
        prependAnchor?.let { anchor ->
            prependAnchor = null
            if (items.size > anchor.sizeBefore) {
                val index = items.indexOfFirst { it.id == anchor.itemId }
                if (index >= 0) {
                    listState.scrollToItem(index + HEADER_ITEM_COUNT, -anchor.offset)
                    return@LaunchedEffect
                }
            }
        }
        if (items.isEmpty()) return@LaunchedEffect
        // A new User row at the tail is a send from this composer:
        // ChatViewModel.sendMessage appends it optimistically, and nothing else
        // in the reducer leaves a user message last. Sending is an explicit act,
        // not an incoming message, so it overrides the scrolled-up guard below.
        // Tracked by id because the same row is written again when its images
        // finish storing, and that patch must not re-yank a reader.
        val tailUserId = (items.lastOrNull() as? ChatItem.User)?.id
        val isOwnSend = tailUserId != null && tailUserId != followedOwnSendId
        if (tailUserId != null) followedOwnSendId = tailUserId
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()
        // Follow new/streaming messages only when the user is already at (or
        // one item away from) the bottom, or before first layout — never yank
        // them back down while they're scrolled up reading history.
        val nearBottom = lastVisible == null || lastVisible.index >= info.totalItemsCount - 2
        // A conversation lays out at the top, so `nearBottom` is false the
        // first time this runs and the guard below — meant to protect someone
        // scrolled up reading history — was refusing to move on open. Opening
        // a chat has to land on the newest message; only after that does
        // "don't yank them back down" apply.
        if (!isOwnSend && landedOnNewest && !nearBottom) return@LaunchedEffect
        // A header row precedes the messages, so the last list index is
        // items.size + HEADER_ITEM_COUNT - 1, not items.size - 1.
        val lastIndex = items.size + HEADER_ITEM_COUNT - 1
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > lastIndex }
        // Jumping, not animating, on the way in: a cold open should already be
        // at the newest message rather than travel there through the history.
        listState.scrollToBottom(lastIndex, animate = landedOnNewest)
        landedOnNewest = true
    }

    if (items.isEmpty()) {
        Column(
            modifier = transcript
                .fillMaxWidth()
                .padding(top = MaterialTheme.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DateDivider(
                label = dayDividerLabel(System.currentTimeMillis()),
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.md)
            )
            Column(
                modifier = Modifier.padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = if (wasCleared) {
                        "Chat cleared. Start a new conversation."
                    } else {
                        "No messages yet.\nSend a message to get started."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    // Centred within the full-width tap-to-dismiss surface so margins on a
    // wide screen stay tappable too, not just the column itself.
    Box(modifier = transcript, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(max = ChatContentMaxWidth),
            contentPadding = PaddingValues(vertical = MaterialTheme.spacing.sm)
            // No verticalArrangement: the gap is per-item, because a message that
            // continues a run sits tighter than one that starts a new one.
        ) {
            item(key = BEGINNING_ITEM_KEY) {
                Text(
                    // Only the truth once the first row really is on screen —
                    // with a page still to come this is the middle of the
                    // conversation, not its beginning.
                    text = if (hasOlderMessages) "Loading earlier messages…" else "Beginning of conversation",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.md)
                        .padding(bottom = MaterialTheme.spacing.xs)
                )
            }
            // contentType lets Lazy layout keep a reuse pool per ChatItem subtype.
            // Without it all 18 subtypes share one pool, so scrolling from a run of
            // tool cards into message bubbles hands back a slot whose node tree is
            // wrong and the item composes from scratch.
            itemsIndexed(
                items,
                key = { _, item -> item.id },
                contentType = { _, item -> item::class },
            ) { index, item ->
                // Only on the tail of the conversation. A failure with messages
                // after it is history — the user moved on, and offering an action
                // there reads as "something needs doing now" about a turn that
                // died hours ago. (It was always built for these too; a long error
                // message just squeezed the button to zero width, hiding it.)
                val onItemRetry: (() -> Unit)? = if (item.isFailedAssistantTurn() && index == items.lastIndex) {
                    findPrecedingUserMessage(items, index)?.let { user ->
                        // Retried files are local file:// paths from the earlier
                        // send (see ChatFileAttachment) — ChatViewModel.sendMessage
                        // re-stores them the same way it stores a fresh SAF pick,
                        // recovering the original name off the path itself (see
                        // FileAttachmentStoreImpl.writeToAppStorage's own doc).
                        { onRetry(user.content, user.images, user.files?.map { it.path }) }
                    }
                } else {
                    null
                }
                val startsRun = !continuesRun(items.getOrNull(index - 1), item, timestamps)
                val endsRun = !continuesRun(item, items.getOrNull(index + 1), timestamps)
                // The divider rides along in the message's own lazy item so the
                // list's item count still equals items.size (+ the one header).
                Column {
                    dayLabels.getOrNull(index)?.let { label ->
                        DateDivider(
                            label = label,
                            modifier = Modifier
                                .padding(horizontal = MaterialTheme.spacing.md)
                                .padding(top = MaterialTheme.spacing.sm)
                        )
                    }
                    ChatItemWrapper(
                        item = item,
                        startsRun = startsRun,
                        endsRun = endsRun,
                        timestamps = timestamps,
                        onRespond = onRespond,
                        onApprove = onApprove,
                        onOnboard = onOnboard,
                        onPlanReviewResponse = onPlanReviewResponse,
                        onUlwResponse = onUlwResponse,
                        onGateResolved = onGateResolved,
                        renderMarkdown = renderMarkdown,
                        onRetry = onItemRetry,
                        // Offered on the message itself whenever it failed —
                        // wherever it sits in the conversation, unlike onItemRetry
                        // which is tail-only. An undelivered message stays the
                        // user's problem even after they have sent others.
                        onResend = (item as? ChatItem.User)
                            ?.takeIf { it.state == UserMessageState.FAILED }
                            ?.let { { onResendMessage(it.id) } },
                        hasLiveConnection = hasLiveConnection
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatItemWrapper(
    item: ChatItem,
    startsRun: Boolean = true,
    endsRun: Boolean = true,
    timestamps: Map<String, Long>,
    onRespond: (String) -> Unit,
    onApprove: (itemId: String, approved: Boolean, scope: String, mode: String?, feedback: String?) -> Unit,
    onOnboard: (method: String, inviteCode: String?, payment: Double?) -> Unit,
    onPlanReviewResponse: (message: String) -> Unit,
    onUlwResponse: (action: String, turns: Int?, mode: String?) -> Unit,
    onGateResolved: (itemId: String) -> Unit,
    renderMarkdown: Boolean,
    onRetry: (() -> Unit)? = null,
    onResend: (() -> Unit)? = null,
    hasLiveConnection: Boolean = true
) {
    // One timestamp per run, on its last message — a time on every bubble in a
    // burst repeats the same minute three times over.
    val timestamp = timestamps[item.id]?.takeIf { endsRun }?.let(::formatMessageTime)
    // The cards' own callbacks carry the answer but not which card gave it;
    // this is the one place that still has `item` in scope, so the gate's id
    // is reported from here (see ChatViewModel.markGateResolved).
    val bubble: @Composable () -> Unit = {
        ChatItemBubble(
            item = item,
            timestamp = timestamp,
            onRespond = { answer -> onGateResolved(item.id); onRespond(answer) },
            onApprove = { approved, scope, mode, feedback ->
                onGateResolved(item.id)
                onApprove(item.id, approved, scope, mode, feedback)
            },
            onOnboard = { method, code, payment ->
                onGateResolved(item.id)
                onOnboard(method, code, payment)
            },
            onPlanReviewResponse = { message -> onGateResolved(item.id); onPlanReviewResponse(message) },
            onUlwResponse = { action, turns, mode ->
                onGateResolved(item.id)
                onUlwResponse(action, turns, mode)
            },
            renderMarkdown = renderMarkdown,
            showSender = startsRun,
            onRetry = onRetry,
            hasLiveConnection = hasLiveConnection,
            onResend = onResend
        )
    }
    // The run boundary carries the spacing: `lg` between runs, `xs` inside one,
    // so a burst from one sender reads as a block rather than as N messages.
    val topGap = if (startsRun) MaterialTheme.spacing.lg else MaterialTheme.spacing.xs
    if (item is ChatItem.User || item is ChatItem.Agent) {
        Box(modifier = Modifier.padding(top = topGap)) { bubble() }
    } else {
        Box(
            modifier = Modifier.padding(top = topGap, start = MaterialTheme.spacing.lg, end = MaterialTheme.spacing.lg)
        ) { bubble() }
    }
}

/** Messages group when they are from the same sender and less than a minute apart. */
private const val RUN_WINDOW_MS = 60_000L

/**
 * Anything that isn't a plain message — a tool call, a gate, a status card —
 * returns null and so always breaks the run: it is a different kind of thing,
 * and burying it inside a burst of bubbles would hide it.
 */
private fun ChatItem.senderKey(): String? = when (this) {
    is ChatItem.User -> "user"
    is ChatItem.Agent, is ChatItem.Turn -> "agent"
    else -> null
}

private fun continuesRun(prev: ChatItem?, next: ChatItem?, timestamps: Map<String, Long>): Boolean {
    if (prev == null || next == null) return false
    val a = prev.senderKey() ?: return false
    if (a != next.senderKey()) return false
    val ta = timestamps[prev.id] ?: return false
    val tb = timestamps[next.id] ?: return false
    return tb - ta in 0..RUN_WINDOW_MS
}

/**
 * True for a failed assistant turn produced by
 * [ai.openonion.oochat.domain.model.ChatEventReducer]'s
 * `ConnectionErrorOccurred` branch — mirrors the two shapes `TurnBubble`/
 * `ThinkingBubble` render as an error: an in-flight [ChatItem.Turn] whose
 * thinking failed with no reply, or a standalone failed [ChatItem.Thinking].
 * Drives whether a "Retry" action is offered on this item.
 */
private fun ChatItem.isFailedAssistantTurn(): Boolean = when (this) {
    is ChatItem.Thinking -> status == ThinkingStatus.ERROR
    is ChatItem.Turn -> thinking?.status == ThinkingStatus.ERROR && agent == null
    else -> false
}

/**
 * The chat list has no explicit link from a failed assistant turn back to
 * the [ChatItem.User] message that triggered it, so "Retry" resolves it by
 * scanning backward for the nearest preceding user message. Returns the
 * whole item so images survive the retry.
 */
private fun findPrecedingUserMessage(items: List<ChatItem>, index: Int): ChatItem.User? {
    for (i in index - 1 downTo 0) {
        val candidate = items[i]
        if (candidate is ChatItem.User) return candidate
    }
    return null
}

/**
 * The "Beginning of conversation" row before the messages. Date dividers are
 * not counted: they render inside their own message's lazy item.
 */
private const val HEADER_ITEM_COUNT = 1

private const val BEGINNING_ITEM_KEY = "beginning-of-conversation"

/**
 * How close to the top a page of older history is fetched. Not zero: the
 * reader must still be standing on a real message when the page lands, so the
 * scroll can be restored relative to it.
 */
private const val OLDER_PAGE_PREFETCH_INDEX = 5

/**
 * Where the reader was when a page of older history was requested — the id of
 * the topmost visible message and its pixel offset within the viewport, plus
 * the item count at the time, which is what tells a page that actually landed
 * from one that came back empty.
 */
private data class PrependAnchor(val itemId: String, val offset: Int, val sizeBefore: Int)

/**
 * [LazyListState.animateScrollToItem] aligns the item's top with the
 * viewport; for a taller-than-viewport last message that leaves the newest
 * text off-screen, so scroll the remainder to bring its bottom edge into view.
 */
private suspend fun LazyListState.scrollToBottom(lastIndex: Int, animate: Boolean) {
    if (animate) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == lastIndex } ?: return
    val overshoot = last.offset + last.size - info.viewportEndOffset
    if (overshoot > 0) {
        if (animate) animateScrollBy(overshoot.toFloat()) else scrollBy(overshoot.toFloat())
    }
}

fun formatMessageTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)

// Hoisted like the two below it. ofPattern parses the pattern and builds a
// formatter tree each call, and this one runs once per visible bubble per
// recomposition — the day formats were already constants, this one was missed.
private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")
private val DAY_FORMAT = DateTimeFormatter.ofPattern("MMMM d")
private val DAY_WITH_YEAR_FORMAT = DateTimeFormatter.ofPattern("MMMM d, yyyy")

/** "Today" / "Yesterday" / "March 4" (with the year once it isn't this one). */
internal fun dayDividerLabel(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    labelForDate(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate(), LocalDate.now(zone))

private fun labelForDate(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(if (date.year == today.year) DAY_FORMAT else DAY_WITH_YEAR_FORMAT)
}

/**
 * One entry per item: the divider label where the calendar day changes, null
 * elsewhere. Items with no timestamp (tool calls, gates) inherit the day of the
 * last dated message so they never force a spurious divider, and a run with no
 * timestamps at all simply gets none.
 *
 * [dayCache] carries the epoch-millis-to-date conversions across calls, so an
 * arriving message costs one zone conversion rather than one per item in the
 * conversation. It is keyed on the timestamp, not the item id, so the messages
 * of a burst share an entry and a re-persisted item cannot leave a stale one.
 */
private fun dayDividerLabels(
    items: List<ChatItem>,
    timestamps: Map<String, Long>,
    dayCache: MutableMap<Long, LocalDate>
): List<String?> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var previousDate: LocalDate? = null
    return items.map { item ->
        val date = timestamps[item.id]
            ?.let { millis ->
                dayCache.getOrPut(millis) { Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() }
            }
            ?: previousDate
        if (date != null && date != previousDate) {
            previousDate = date
            labelForDate(date, today)
        } else {
            null
        }
    }
}
