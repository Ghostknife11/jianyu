package org.jianyu.core.domain

import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyEvent

internal fun FamilyEvent.isVisibleInSharedTimeline(): Boolean =
    visibility.trim().lowercase() in setOf("guardians", "family", "shared-with-child")

/** A broader later event never widens the visibility of a choice's restricted source. */
internal fun FamilyChoice.hasRestrictedLinkedEvent(events: List<FamilyEvent>): Boolean =
    events.any { event ->
        (event.eventId == sourceEventId || event.payload["choiceId"] == id) &&
            !event.isVisibleInSharedTimeline()
    }
