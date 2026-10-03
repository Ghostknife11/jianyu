package org.jianyu.app

private const val MAX_INCOMING_SHARE_CHARS = 800

/**
 * Turns Android share-sheet text into a reviewable, ephemeral recommendation draft.
 * It does not persist, classify, fetch, or disclose the incoming content.
 */
internal fun buildIncomingShareDraft(subject: CharSequence?, text: CharSequence?): String? {
    val parts = listOfNotNull(
        subject?.toString()?.trim()?.takeIf(String::isNotEmpty),
        text?.toString()?.trim()?.takeIf(String::isNotEmpty),
    ).distinct()
    if (parts.isEmpty()) return null
    return parts.joinToString("\n").take(MAX_INCOMING_SHARE_CHARS).trim().ifEmpty { null }
}
