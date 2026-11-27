package eu.kanade.tachiyomi.ui.reader

/**
 * The one place a chapter id becomes the reader's chapter object: the reader's own list wins, else one cached copy.
 * Two objects for the same chapter would each write their own (stale) read / last-page state.
 */
internal fun <T> resolveCached(id: Long, listed: List<T>, idOf: (T) -> Long?, cache: java.util.concurrent.ConcurrentMap<Long, T>, create: () -> T): T =
    // computeIfAbsent, not getOrPut: the stats estimate resolves on another thread while a chapter loads.
    listed.firstOrNull { idOf(it) == id } ?: cache.computeIfAbsent(id) { create() }
