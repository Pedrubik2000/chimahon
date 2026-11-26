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
    /** Rates the word's card (1 Again .. 4 Easy); answers the undo token (the card id), or null = not saved (no card of it). Blocking. */
    fun ratePopupCard(context: Context, word: String, reading: String, ease: Int, answerMs: Long): String? {
        val card = Cards.find(context, word, reading) ?: return null
        val srs = Cards.store(context)
        val sentence = chimahon.ocr.KumapieColours.sentence
        val place = chimahon.ocr.KumapieColours.place
        val token = PopupRate.rate(srs, card.id, ease, answerMs) { SyncTrigger.request(context, "popup rating") }?.toString()
        // where it happened is a context of the card (local only, never synced), as the rating row has always saved
        if (token != null && place != null) Cards.addContext(context, card.id, place.first, place.second, null, sentence)
        return token
    }

    /** Takes back the rating [token] ([ratePopupCard]'s answer); false = not undone. Blocking. */
    fun undoPopupCard(context: Context, token: String): Boolean {
        val id = token.toLongOrNull() ?: return false
        return PopupRate.undo(Cards.store(context), id) { SyncTrigger.request(context, "popup undo") }
    }
}
