package com.ravy.assistant.gmail

/**
 * Tracks "what email are we talking about" across a conversation so
 * "reply to this", "archive it", or "open the second one" resolve to a
 * real message id instead of requiring the user to repeat search terms
 * every time. In-memory only, cleared when the process restarts — this is
 * conversational convenience, not a persisted record.
 */
object GmailSessionContext {
    /** The last list of results from an inbox summary or search — lets "the second one" resolve by position. */
    var lastResults: List<GmailSummaryItem> = emptyList()

    /** The single message currently "in focus" — set whenever one is opened/referenced, read by "this"/"that"/"it". */
    var currentMessage: GmailSummaryItem? = null

    fun setResults(results: List<GmailSummaryItem>) {
        lastResults = results
        currentMessage = results.firstOrNull()
    }

    fun focus(item: GmailSummaryItem) {
        currentMessage = item
    }

    fun resolveOrdinal(text: String): GmailSummaryItem? {
        val ordinalWords = mapOf(
            "first" to 0, "1st" to 0, "second" to 1, "2nd" to 1, "third" to 2, "3rd" to 2,
            "fourth" to 3, "4th" to 3, "fifth" to 4, "5th" to 4
        )
        for ((word, index) in ordinalWords) {
            if (text.contains(word)) return lastResults.getOrNull(index)
        }
        Regex("""number (\d+)""").find(text)?.let {
            val idx = (it.groupValues[1].toIntOrNull() ?: 0) - 1
            return lastResults.getOrNull(idx)
        }
        return null
    }
}
