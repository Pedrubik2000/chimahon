package eu.kanade.tachiyomi.ui.glass.popup

import chimahon.srs.Srs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A rating made in the reader's lookup popup: ONE writer, kumahon's own store ([Srs.rate], never kumapie's provider), then the sync with
 * the hub ([afterWrite]) so the rating reaches kumapie as a revlog event. Answers the revlog id of the rating it wrote (the undo
 * token), or null = no such card.
 */
object PopupRate {
    fun rate(srs: Srs, cardId: Long, ease: Int, answerMs: Long, afterWrite: () -> Unit): Long? {
        if (ease !in 1..4) return null
        // check and write in one transaction: the sync worker's take() (same db, same lock) cannot delete the card in between
        val id = srs.db.tx {
            if (srs.card(cardId) == null) return@tx null
            Srs.newId().also { srs.rate(cardId, ease, answerMs = answerMs.takeIf { it > 0 }, revlogId = it) }
        } ?: return null
        afterWrite()
        return id
    }

    /** Takes back THIS popup's rating ([revlogId], [rate]'s answer); false = not undone (something newer is on the card, or it is gone). */
    fun undo(srs: Srs, cardId: Long, revlogId: Long, afterWrite: () -> Unit): Boolean {
        // the check and the undo in one transaction: a sync taking another device's rating cannot slip in between
        val done = srs.db.tx {
            if (srs.card(cardId)?.lastReview == null || srs.lastRevlogId(cardId) != revlogId) false else { srs.undo(cardId); true }
        }
        if (!done) return false
        afterWrite()
        return true
    }
}

/** One rating per word per popup open: [begin] is true once until [fail] gives the turn back (the rating did not save). */
class RateOnce {
    private val taken = AtomicBoolean(false)
    fun begin(): Boolean = taken.compareAndSet(false, true)
    fun fail() = taken.set(false)
    /** Undo took the rating back: the word may be rated again. */
    fun reset() = taken.set(false)
}
