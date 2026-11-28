package eu.kanade.tachiyomi.ui.glass.popup

import android.content.Context
import chimahon.srs.Cards
import chimahon.srs.SyncTrigger

/**
 * The ONE place where the lookup popup's rating buttons write (Pedro, 2026-11-25): kumahon's OWN Cards store, never kumapie's provider
 * (so one click = one writer), off the main thread (the callers use Dispatchers.IO). The sync with the hub follows at once
 * ([SyncTrigger]), so the rating reaches kumapie as a revlog event (ids kept: ms * 1000 + 0..999, dedup by id).
 */
object PopupRating {
    /**
     * Rates the word's card (1 Again .. 4 Easy); answers the undo token (`card:revlogId`), or null = not saved (no card of it). Blocking.
     * [text] = the word as the page has it: the card is the one the popup's state found ([Cards.shownCard]).
     * [newCardMeaning] not null = the word is blue from a card on another device (Pedro, 2026-11-28: "Rate it here"): without a card of
     * its own, and only when another device really has this word with this reading ([Cards.cardedElsewhere]), the rating first makes
     * the card here with that meaning and this context, as mining it would. Card and rating are one transaction ([PopupRate.rateNew]):
     * a rating that is not written leaves no card.
     */
    fun ratePopupCard(context: Context, word: String, reading: String, ease: Int, answerMs: Long, text: String = "", newCardMeaning: String? = null): String? {
        val sentence = chimahon.ocr.KumapieColours.sentence
        val place = chimahon.ocr.KumapieColours.place
        val had = Cards.shownCard(context, word, reading, text)
        val srs = Cards.store(context)
        val synced = { SyncTrigger.request(context, "popup rating") }
        if (had == null) {
            if (newCardMeaning == null || word.isBlank() || !Cards.cardedElsewhere(context, word, reading)) return null
            val (card, rev) = PopupRate.rateNew(srs, ease, answerMs, synced) {
                Cards.addMined(context, word, reading, sentence, newCardMeaning, place?.first, place?.second).id
            } ?: return null.also { chimahon.ocr.KumapieColours.clear() } // addMined recoloured the page for a card that is gone again
            return "$card:$rev" // addMined saved this context and recoloured the page
        }
        val card = had
        val rev = PopupRate.rate(srs, card.id, ease, answerMs, synced) ?: return null
        // where it happened is a context of the card (local only, never synced), as the rating row has always saved
        // the rating is committed: a failure from here on must not report "not saved" (a second tap would rate twice)
        runCatching {
            if (place != null) Cards.addContext(context, card.id, place.first, place.second, null, sentence)
            chimahon.ocr.KumapieColours.clear() // the page's colours follow the new state
        }.onFailure { android.util.Log.w("KumahonPopup", "rating saved, context/colours failed", it) }
        return "${card.id}:$rev"
    }

    /** Takes back the rating [token] ([ratePopupCard]'s answer) when it is still the card's newest; false = not undone. Blocking. */
    fun undoPopupCard(context: Context, token: String): Boolean {
        val (card, rev) = token.split(":").mapNotNull { it.toLongOrNull() }.takeIf { it.size == 2 } ?: return false
        val undone = PopupRate.undo(Cards.store(context), card, rev) { SyncTrigger.request(context, "popup undo") }
        if (undone) chimahon.ocr.KumapieColours.clear()
        return undone
    }
}
