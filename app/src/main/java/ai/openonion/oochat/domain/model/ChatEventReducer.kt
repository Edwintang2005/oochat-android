package ai.openonion.oochat.domain.model

/**
 * Result of folding one [ChatEvent] into the current chat list.
 *
 * @param chatItems the new chat list to render
 * @param itemToPersist non-null when [chatItems] gained/changed an item the
 *   caller should write to local history (matches [ChatEvent.ChatItemReceived]/
 *   [ChatEvent.ChatItemUpdated]'s own item, or the freshly-built [ChatItem.Turn]
 *   for [ChatEvent.OutputReceived]) — null when the event was a no-op (ignored
 *   id, duplicate content, or [ChatEvent.Waiting]).
 */
data class ReduceResult(
    val chatItems: List<ChatItem>,
    val itemToPersist: ChatItem? = null
)

/**
 * Pure event-to-chat-list folding logic. Uses [stableAssistantId] so a
 * locally-minted Turn matches ProtocolParser's id for dedupeUI.
 */
object ChatEventReducer {

    /**
     * @param resolvedGateIds ids of blocking cards (approval, ask_user, plan
     *   review, …) the user has already answered. Anything still pending stays
     *   pinned to the bottom of the list — see
     *   [ChatEventHelpers.appendPinningGates].
     */
    fun reduce(
        current: List<ChatItem>,
        event: ChatEvent,
        ignoredIds: Set<String>,
        resolvedGateIds: Set<String> = emptySet()
    ): ReduceResult =
        when (event) {
            is ChatEvent.ChatItemReceived -> {
                if (event.item.id in ignoredIds) {
                    ReduceResult(current)
                } else if (event.item is ChatItem.OnboardRequired) {
                    val newList = ChatEventHelpers.replaceOnboardingCard(current, event.item)
                    ReduceResult(newList, event.item)
                } else if (event.item is ChatItem.OnboardingFailed) {
                    val newList = ChatEventHelpers.replaceOnboardingCard(
                        current,
                        event.item,
                        filterCondition = {
                            (it is ChatItem.OnboardRequired || it is ChatItem.OnboardingFailed) &&
                                it.id == event.item.id
                        }
                    )
                    ReduceResult(newList, event.item)
                } else if (event.item is ChatItem.OnboardSuccess) {
                    val cleaned = ChatEventHelpers.removeOnboardingCards(current)
                    ReduceResult(cleaned + event.item, event.item)
                } else if (event.item is ChatItem.Agent && event.item.isImageOnlyMarker) {
                    // Parser can't merge itself (no lookback); falls back to
                    // floating item when no Turn is running.
                    val runningTurnIndex = current.indexOfLast {
                        it is ChatItem.Turn && it.thinking?.status == ThinkingStatus.RUNNING
                    }
                    if (runningTurnIndex >= 0) {
                        val runningTurn = current[runningTurnIndex] as ChatItem.Turn
                        val existingImages = runningTurn.agent?.images.orEmpty()
                        // A redelivered frame (reconnect replay — see
                        // ProtocolParser's own comment on "agent_image")
                        // must not duplicate an image already merged in.
                        val newImages = event.item.images.orEmpty().filterNot { it in existingImages }
                        if (newImages.isEmpty()) {
                            ReduceResult(current)
                        } else {
                            val mergedAgent = runningTurn.agent?.copy(images = existingImages + newImages)
                                ?: event.item.copy(images = newImages)
                            val mergedTurn = runningTurn.copy(agent = mergedAgent)
                            val newList = current.toMutableList().also { it[runningTurnIndex] = mergedTurn }
                            ReduceResult(newList, mergedTurn)
                        }
                    } else {
                        ReduceResult(ChatEventHelpers.appendPinningGates(current, event.item, resolvedGateIds), event.item)
                    }
                } else if (
                    event.item is ChatItem.Turn && event.item.agent != null &&
                    !event.fromSessionSnapshot
                ) {
                    // A reply arriving for the turn whose footer is already in
                    // flight — one turn is one item, so fold rather than append.
                    //
                    // Snapshot items are excluded because a session_sync
                    // replays every assistant message the session holds, and it
                    // streams after each trace entry — so a reply that finished
                    // long ago routinely arrives milliseconds after llm_call.
                    // Folding one of those consumed the pending turn (the
                    // "flashing" indicator), moved the old reply, and left it
                    // wearing a footer describing a different turn.
                    val merged = ChatEventHelpers.mergeIntoPendingTurn(current, event.item)
                    if (merged != null) {
                        ReduceResult(merged, merged.first { it.id == event.item.id })
                    } else {
                        ReduceResult(ChatEventHelpers.appendPinningGates(current, event.item, resolvedGateIds), event.item)
                    }
                } else {
                    ReduceResult(ChatEventHelpers.appendPinningGates(current, event.item, resolvedGateIds), event.item)
                }
            }

            is ChatEvent.ChatItemUpdated -> {
                if (event.item.id in ignoredIds) {
                    ReduceResult(current)
                } else {
                    // Some servers emit an "update" frame (e.g. llm_result
                    // for a Thinking bubble) without a preceding "received"
                    // — fall back to appending if no item with the same id
                    // exists yet.
                    val existing = current.indexOfFirst { it.id == event.item.id }
                    val existingItem = current.getOrNull(existing)
                    val updatedItem = if (
                        event.item is ChatItem.Turn && existingItem is ChatItem.Turn &&
                        event.item.agent == null && existingItem.agent != null
                    ) {
                        // llm_result rebuilds the Turn from scratch carrying
                        // only the new thinking status (agent defaults to
                        // null) — without this, it would blindly wipe out an
                        // agent_image already merged onto this same Turn id
                        // (see ChatItemReceived's agent_image branch above).
                        event.item.copy(agent = existingItem.agent)
                    } else if (
                        event.item is ChatItem.ToolCall && existingItem is ChatItem.ToolCall &&
                        event.item.args == null && existingItem.args != null
                    ) {
                        // "tool_result" never carries an "args" field of its
                        // own (ProtocolParser's "tool_result" branch has no
                        // args = ... line) — a blind replace here would wipe
                        // the command/path/etc. the "tool_call" RUNNING item
                        // carried, which is exactly what the DONE/ERROR card
                        // needs to still show what actually ran. Found on
                        // device: every finished tool card lost its
                        // command/path the moment its result arrived.
                        event.item.copy(args = existingItem.args)
                    } else {
                        event.item
                    }
                    // No dedupeUI on either path, and it isn't an oversight:
                    // the replace writes at a known index, and the append only
                    // happens because the search above found no item with this
                    // id. Neither can produce a duplicate, so the pass was
                    // building a hash set over the whole conversation on every
                    // update frame — of which a single turn emits many — to
                    // discover it had nothing to remove.
                    val newList = if (existing >= 0) {
                        current.toMutableList().also { it[existing] = updatedItem }
                    } else {
                        current + updatedItem
                    }
                    ReduceResult(newList, updatedItem)
                }
            }

            is ChatEvent.OutputReceived -> {
                val trimmedResult = event.result.trim()
                // Same content-derived id ProtocolParser uses, so dedupeUI()
                // collapses a later arrival of the same reply.
                val tid = if (trimmedResult.isNotBlank())
                    stableAssistantId(trimmedResult)
                else null

                // Drop the OUTPUT entirely if the user cleared this content
                // before, or if a bubble with this content is already rendered.
                if (tid != null && tid in ignoredIds) {
                    ReduceResult(current)
                } else {
                    val hasResponse = current.any { item ->
                        when (item) {
                            is ChatItem.Agent -> item.content == trimmedResult
                            is ChatItem.Turn -> item.agent?.content == trimmedResult
                            else -> false
                        }
                    }
                    // Fold into the footer this reply belongs to before
                    // resolving: resolveRunningItems would give that Turn an
                    // empty agent and so hide it as a merge target. Attempted
                    // even when the reply is already on screen — a session_sync
                    // can deliver it ahead of OUTPUT, and the pending footer
                    // still belongs to it.
                    val turn = tid?.let {
                        ChatItem.Turn(id = it, agent = ChatItem.Agent(id = it, content = trimmedResult))
                    }
                    val merged = turn?.let { ChatEventHelpers.mergeIntoPendingTurn(current, it) }
                    if (merged != null) {
                        val resolved = merged.resolveRunningItems()
                        ReduceResult(resolved, resolved.first { it.id == tid })
                    } else if (!hasResponse && turn != null) {
                        ReduceResult(
                            ChatEventHelpers.appendPinningGates(current.resolveRunningItems(), turn, resolvedGateIds),
                            turn
                        )
                    } else {
                        // OUTPUT is the completion signal; resolve any dangling
                        // in-flight items.
                        ReduceResult(current.resolveRunningItems())
                    }
                }
            }

            is ChatEvent.Waiting -> ReduceResult(current)

            // Handled by the caller against local history, not here — see
            // ChatEvent.ServerTranscriptReceived's own doc.
            is ChatEvent.ServerTranscriptReceived -> ReduceResult(current)

            // Handled by the caller (ChatViewModel decides whether to revert
            // a disconnect-triggered failure) — not a chat-list mutation on
            // its own. See ChatEvent.SessionStatusReceived's own doc.
            is ChatEvent.SessionStatusReceived -> ReduceResult(current)

            is ChatEvent.ConnectionErrorOccurred -> {
                // Attach to whichever Turn/Thinking bubble was in flight —
                // reads as "the agent's reply was this error" — or synthesize
                // a standalone failed item if nothing was running, so this
                // shows up in the conversation exactly like a real reply
                // would, rather than a banner/snackbar outside the message
                // list (see ChatEvent.ConnectionErrorOccurred's own doc for
                // why this isn't a ConnectionState transition).
                var attachedItem: ChatItem? = null
                val updated = current.map { item ->
                    when {
                        item is ChatItem.Thinking && item.status == ThinkingStatus.RUNNING -> {
                            item.copy(status = ThinkingStatus.ERROR, content = event.message)
                                .also { attachedItem = it }
                        }
                        // Same in-flight condition as resolveRunningItems — a Turn
                        // can dangle here too if the connection died before OUTPUT arrived.
                        item is ChatItem.Turn && (item.thinking?.status == ThinkingStatus.RUNNING || item.agent == null) -> {
                            item.copy(
                                thinking = item.thinking?.copy(status = ThinkingStatus.ERROR, content = event.message),
                                agent = item.agent ?: ChatItem.Agent(id = item.id, content = event.message)
                            ).also { attachedItem = it }
                        }
                        else -> item
                    }
                }
                val existingAttachment = attachedItem
                if (existingAttachment != null) {
                    ReduceResult(updated, existingAttachment)
                } else {
                    val standalone = ChatItem.Thinking(
                        id = stableAssistantId(event.message),
                        status = ThinkingStatus.ERROR,
                        content = event.message
                    )
                    ReduceResult(ChatEventHelpers.appendPinningGates(current, standalone, resolvedGateIds), standalone)
                }
            }
        }
}
