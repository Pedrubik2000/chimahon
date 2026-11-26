package eu.kanade.tachiyomi.ui.glass.popup

import chimahon.srs.Srs

/**
 * A rating made in the reader's lookup popup: ONE writer, kumahon's own store ([Srs.rate], never kumapie's provider), then the sync with
 * the hub ([afterWrite]) so the rating reaches kumapie as a revlog event. Answers the card id (the undo token), or null = no such card.
 */
object PopupRate {
    fun rate(srs: Srs, cardId: Long, ease: Int, answerMs: Long, afterWrite: () -> Unit): Long? {
        if (ease !in 1..4 || srs.card(cardId) == null) return null
        srs.rate(cardId, ease, answerMs = answerMs.takeIf { it > 0 })
        afterWrite()
        return cardId
    }

    /** Takes the rating back (the card's last standing one); true = something was undone. */
    fun undo(srs: Srs, cardId: Long, afterWrite: () -> Unit): Boolean {
        val c = srs.card(cardId) ?: return false
        if (c.lastReview == null) return false
        srs.undo(cardId)
        afterWrite()
        return true
    }
}
