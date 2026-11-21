package eu.kanade.tachiyomi.ui.glass.discover

import eu.kanade.tachiyomi.data.anilist.AniListLibrary.Entry
import kotlin.math.sqrt
import kotlin.random.Random

/** A candidate with Next Volume's numbers: [rank] = 0.6·z(fit) + 0.4·z(averageScore), [match] = rank percentile. */
data class Pick(val entry: Entry, val fit: Double, val rank: Double, val match: Int) {
    val mediaId: Long get() = entry.mediaId
}

/** One "Why this one" line, from data that exists (never invented, never known %). */
sealed interface Reason {
    /** AniList readers of [by] (a title Pedro completed) recommend it. */
    data class Recommended(val by: String) : Reason

    /** [features] Pedro tends to like, with completed [titles] that carry them. */
    data class Taste(val features: List<String>, val titles: List<String>) : Reason

    data class Finished(val volumes: Int) : Reason

    data class Score(val averageScore: Int) : Reason
}

/**
 * Port of `tools/mangapick/index.html` (`build`, `pick`, `duel`, `whyOf`), same numbers, on the AniList list cache.
 * [extra] = titles not on the list (AniList recommendations) that join the candidates when the list has few.
 * Differences from the page: no filters (all candidates count), a tag is a spoiler when AniList marks it a media or a
 * general spoiler (the page only looks at media spoilers), the shuffle in [duel] is a fair one.
 */
class NextVolumeRanking(all: List<Entry>, extra: List<Entry> = emptyList()) {

    private val rated = all.filter { it.status in RATED }

    /** Completed or rereading titles the taste is learned from. */
    val completed: Int = rated.count { it.status != "DROPPED" }
    val dropped: Int = rated.size - completed

    /** Taste weight per genre/tag. */
    val weights: Map<String, Double>

    /** Every candidate, best rank first. */
    val ranked: List<Pick>

    init {
        val sc = rated.map { it.score }.filter { it > 0 }
        val mean = sc.sum().toDouble() / (sc.size.takeIf { it > 0 } ?: 1)
        val sd = sqrt(sc.sumOf { (it - mean) * (it - mean) } / (sc.size.takeIf { it > 0 } ?: 1)).orOne()
        val w = HashMap<String, Double>()
        val n = HashMap<String, Int>()
        for (d in rated) {
            val v = (if (d.score > 0) (d.score - mean) / sd else 0.0) + if (d.status == "DROPPED") -1.2 else 0.3
            for (f in features(d)) {
                w[f] = (w[f] ?: 0.0) + v
                n[f] = (n[f] ?: 0) + 1
            }
        }
        // shrink toward 0 so a tag seen once can't dominate
        weights = w.mapValues { (f, s) -> s / (n.getValue(f) + 2) }

        val onList = all.filter { it.status in CANDIDATE && it.mediaStatus != "CANCELLED" }
        val ids = all.mapTo(HashSet()) { it.mediaId }
        val cands = onList + extra.filter { it.mediaId !in ids && it.mediaStatus != "CANCELLED" }.distinctBy { it.mediaId }
        val fits = cands.map { d ->
            val fs = features(d).filter { it in weights }
            if (fs.isEmpty()) 0.0 else fs.sumOf { weights.getValue(it) } / sqrt(fs.size.toDouble())
        }
        val zf = z(fits)
        val za = z(cands.map { it.averageScore?.toDouble() })
        val ranks = cands.indices.map { 0.6 * zf(fits[it]) + 0.4 * za(cands[it].averageScore?.toDouble()) }
        val match = IntArray(cands.size)
        cands.indices.sortedBy { ranks[it] }.forEachIndexed { i, c ->
            match[c] = Math.round(100.0 * (i + 1) / cands.size).toInt()
        }
        ranked = cands.indices.map { Pick(cands[it], fits[it], ranks[it], match[it]) }.sortedByDescending { it.rank }
    }

    /** Weighted toward the top 25 (weights 1/(i+3)), never [last]. */
    fun pick(hidden: Set<Long>, last: Long?, random: Random): Pick? {
        val list = ranked.filter { it.mediaId !in hidden && it.mediaId != last }.take(25)
        if (list.isEmpty()) return null
        val w = list.indices.map { 1.0 / (it + 3) }
        var r = random.nextDouble() * w.sum()
        var i = 0
        while (i < list.lastIndex) {
            r -= w[i]
            if (r <= 0) break
            i++
        }
        return list[i]
    }

    /** Knockout of 8 taken at random from the top 16. */
    fun duel(hidden: Set<Long>, random: Random): Duel =
        Duel.start(ranked.filter { it.mediaId !in hidden }.take(16).shuffled(random).take(8))

    /** Up to 4 reasons; [recommendedBy] = recommended mediaId → the completed title it was recommended from. */
    fun why(p: Pick, recommendedBy: Map<Long, String>): List<Reason> {
        val e = p.entry
        val out = mutableListOf<Reason>()
        recommendedBy[e.mediaId]?.let { out += Reason.Recommended(it) }
        val top = features(e).distinct().filter { (weights[it] ?: 0.0) > 0.05 }.sortedByDescending { weights[it] }.take(2)
        if (top.isNotEmpty()) {
            val titles = rated.filter { it.status != "DROPPED" }
                .map { it to features(it).count { f -> f in top } }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenByDescending { it.first.score })
                .take(3)
                .map { name(it.first) }
            out += Reason.Taste(top, titles)
        }
        val volumes = e.volumes
        if (e.mediaStatus == "FINISHED" && volumes != null && volumes > 0) out += Reason.Finished(volumes)
        e.averageScore?.let { out += Reason.Score(it) }
        return out.take(4)
    }

    companion object {
        private val RATED = setOf("COMPLETED", "REPEATING", "DROPPED")
        private val CANDIDATE = setOf("PLANNING", "CURRENT", "PAUSED")

        /** Genres + up to 6 non-spoiler tags of rank 60+ (the page's `feat`). */
        fun features(e: Entry): List<String> =
            e.genres + e.tags.filter { it.rank >= 60 && !it.spoiler }.map { it.name }.take(6)

        fun name(e: Entry): String = e.native ?: e.romaji ?: e.english ?: ""

        private fun Double.orOne() = if (this == 0.0 || isNaN()) 1.0 else this

        /** The page's z(): nulls count as 0 in mean and sd, and score as the mean. */
        private fun z(values: List<Double?>): (Double?) -> Double {
            val v = values.map { it ?: 0.0 }
            val n = v.size.takeIf { it > 0 } ?: 1
            val m = v.sum() / n
            val s = sqrt(v.sumOf { (it - m) * (it - m) } / n).orOne()
            return { x -> ((x ?: m) - m) / s }
        }
    }
}

/** The page's knockout: winners of a round meet in order; an odd one out waits for the next round. */
data class Duel(
    val all: List<Pick>,
    val pool: List<Pick>,
    val next: List<Pick> = emptyList(),
    val done: Int = 0,
    val out: Set<Long> = emptySet(),
) {
    val total: Int get() = (all.size - 1).coerceAtLeast(0)
    val pair: Pair<Pick, Pick>? get() = if (pool.size >= 2) pool[0] to pool[1] else null
    val winner: Pick? get() = if (next.isEmpty() && pool.size == 1) pool[0] else null

    fun choose(first: Boolean): Duel {
        val (a, b) = pair ?: return this
        val keep = if (first) a else b
        val lose = if (first) b else a
        val d = Duel(all, pool.drop(2), next + keep, done + 1, out + lose.mediaId)
        return if (d.pool.size < 2) d.copy(pool = d.next + d.pool, next = emptyList()) else d
    }

    companion object {
        fun start(titles: List<Pick>) = Duel(titles, titles)
    }
}
