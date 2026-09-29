package ai.openonion.oochat.domain.model

object ChatEventHelpers {

    fun replaceOnboardingCard(
        items: List<ChatItem>,
        newCard: ChatItem,
        filterCondition: (ChatItem) -> Boolean = {
            it is ChatItem.OnboardRequired || it is ChatItem.OnboardingFailed
        }
    ): List<ChatItem> {
        val existing = items.indexOfFirst(filterCondition)
        val newList = if (existing >= 0) {
            items.toMutableList().also { it[existing] = newCard }
        } else {
            items + newCard
        }
        return newList.dedupeUI()
    }

    fun removeOnboardingCards(items: List<ChatItem>): List<ChatItem> {
        return items.filterNot { item ->
            item is ChatItem.OnboardRequired || item is ChatItem.OnboardingFailed
        }
    }

    /**
     * Folds a content-bearing Turn into the footer-only Turn already in flight,
     * so one LLM turn is one list item.
     *
     * The two arrive under different ids and the wire offers nothing to join
     * them on: `llm_call`/`llm_result` carry the server's call id, while OUTPUT
     * and `session_sync` carry only text, hashed by [stableAssistantId]. So the
     * join is positional — the pending footer is the last Turn still awaiting a
     * reply — and the *content* id wins, because a later `session_sync` replay
     * of the same text can only ever produce that one.
     *
     * Null when there is no pending footer to fold into (a reply with no
     * llm_call before it, or a turn already merged), leaving the caller to
     * append as before.
     */
    fun mergeIntoPendingTurn(items: List<ChatItem>, incoming: ChatItem.Turn): List<ChatItem>? {
        if (incoming.agent == null) return null
        val pendingIndex = items.indexOfLast {
            it is ChatItem.Turn && it.agent == null && it.thinking != null
        }
        if (pendingIndex < 0) return null
        val pending = items[pendingIndex] as ChatItem.Turn
        // OUTPUT is the completion signal, so a still-RUNNING footer is done
        // by the time its reply lands.
        val settled = pending.thinking?.takeIf { it.status == ThinkingStatus.RUNNING }
            ?.copy(status = ThinkingStatus.DONE)
            ?: pending.thinking

        // This reply may already be on screen — a session_sync can carry it
        // before OUTPUT names it. Move the footer onto the item that is
        // already there and drop the placeholder; writing the reply over the
        // placeholder instead would leave two rows sharing an id, and
        // dedupeUI would keep the older one and silently bin the footer.
        val existingIndex = items.indexOfFirst { it.id == incoming.id }
        if (existingIndex >= 0 && existingIndex != pendingIndex) {
            val existing = items[existingIndex]
            val withFooter = (existing as? ChatItem.Turn)
                ?.let { it.copy(thinking = it.thinking ?: settled) }
                ?: return null
            return items.toMutableList()
                .also { it[existingIndex] = withFooter; it.removeAt(pendingIndex) }
        }

        val merged = incoming.copy(thinking = incoming.thinking ?: settled)
        return items.toMutableList()
            .also { it[pendingIndex] = merged }
            .dedupeUI()
    }

    /**
     * Cards that block on the user: they carry buttons the conversation can't
     * proceed past until tapped. Everything else in the list is a transcript
     * the user only reads.
     */
    fun isGate(item: ChatItem): Boolean = when (item) {
        is ChatItem.ApprovalNeeded,
        is ChatItem.AskUser,
        is ChatItem.PlanReviewItem,
        is ChatItem.UlwTurnsReachedItem,
        is ChatItem.OnboardRequired,
        is ChatItem.OnboardingFailed -> true
        // A message typed while a turn is running is held until that turn ends,
        // so everything the turn still emits happened before it. Same pinning
        // for the same reason: appending on top of it put the answer to the
        // previous question underneath the next one.
        is ChatItem.User -> item.queued
        else -> false
    }

    /**
     * Append [item], but keep any still-unanswered [isGate] cards at the very
     * bottom of the list.
     *
     * The agent routinely keeps talking after raising a gate — it emits the
     * approval request and its own commentary in the same breath, and the
     * server additionally replays the whole thread via `session_sync` after
     * every step. Appended chronologically, each of those pushes the approval
     * card further up until its buttons are off-screen and the conversation
     * looks stuck. So new transcript content slots in *above* the trailing run
     * of pending gates, leaving the actionable card pinned against the input
     * bar.
     *
     * Once answered ([resolvedGateIds]) a gate stops being pinned and behaves
     * like ordinary transcript again, so replies to it land underneath where
     * they belong. A gate arriving while another is pending still appends
     * normally — pending gates stay in arrival order among themselves.
     */
    fun appendPinningGates(
        items: List<ChatItem>,
        item: ChatItem,
        resolvedGateIds: Set<String>
    ): List<ChatItem> {
        if (isGate(item)) return (items + item).dedupeUI()

        val firstPendingGate = items.indexOfFirst { isGate(it) && it.id !in resolvedGateIds }
        if (firstPendingGate < 0) return (items + item).dedupeUI()

        // Only a *trailing* run of gates gets jumped over — a pending gate with
        // ordinary transcript after it was already pushed up by an earlier
        // build, and silently reordering that history would move messages the
        // user has already read.
        val tailIsAllGates = items.drop(firstPendingGate).all { isGate(it) }
        if (!tailIsAllGates) return (items + item).dedupeUI()

        return items.toMutableList()
            .also { it.add(firstPendingGate, item) }
            .dedupeUI()
    }
}
