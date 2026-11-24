// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Parser.cs (preloaded maps and
// lookup helpers), Jiten.Parser/Runtime/ParserRuntime.cs (BuildWordMetadata, BuildKanjiBackedWordIds),
// Jiten.Core/Data/JMDict/JMDictHelper.cs (LoadNameOnlyWordIds, LoadExpressionWordIds)
package chimahon.lang.jiten

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Jiten's word database without Postgres: jiten-lexicon.tsv.gz from tools/kumapie/jiten_lexicon.py (format at the top
 * of that script). It stands in for everything Parser.EnsureInitializedAsync preloads and the JmDictCache serves:
 *
 * - `_lookups` (lookup key -> word ids): [lookup], [containsKey], [keyCount]/[keyAt]/[idsAt] to iterate,
 *   [maxLookupKeyLength] (`_maxLookupKeyLength`; Parser builds `_compoundHashSet` from the keys itself).
 * - `WordMeta` (pos, kana/kanji priority scores with uk and archaic folded in, origin, true name): [wordMeta], or the
 *   allocation-free [priorityScore], [hasPos], [primaryPos], [isTrueName].
 * - `_wordFrequencyRanks`: [wordFrequencyRank]. `_nameOnlyWordIds`: [isNameOnly]. `_expressionWordIds`:
 *   [isExpression]. `_kanjiBackedWordIds`: [isKanjiBacked].
 * - `JmDictCache.GetWordsAsync` (words with forms and senses, no glosses): [getWord].
 * - Not here: `WordFrequencyPriors.Current` (Jiten's observed corpus frequencies; null there means "skip the prior"),
 *   per-form frequencies (`WordFormFrequencies`, only for example sentences and direct lookups) and the DeckWord cache.
 *
 * Plus Parser's lookup helpers ([hasLookupWhere], [lookupWithKanaFallback]) and the ten MorphologicalAnalyser callbacks.
 * ponytail: everything sits in primitive arrays on the heap (~95 MB for JMdict + JMnedict in a JVM test); a
 * memory-mapped binary file would take it off the heap if the phone needs it.
 */
class JitenLexicon private constructor(
    // words, ascending id
    private val wordIds: IntArray,
    private val posStart: IntArray,
    private val posTags: ShortArray,
    private val priStart: IntArray,
    private val priTags: ShortArray,
    private val priorityKana: ShortArray,
    private val priorityKanji: ShortArray,
    private val flags: ByteArray,
    private val ranks: IntArray,
    private val blobStart: IntArray,
    private val blob: CharArray,
    private val tags: Array<String>,
    private val tagPos: Array<PartOfSpeech>,
    // lookups, ascending UTF-16 key order
    private val keyStart: IntArray,
    private val keyChars: CharArray,
    private val idStart: IntArray,
    private val ids: IntArray,
    val maxLookupKeyLength: Int,
) {
    val wordCount: Int get() = wordIds.size
    val keyCount: Int get() = keyStart.size - 1

    // --- _lookups ---

    fun keyAt(i: Int): String = String(keyChars, keyStart[i], keyStart[i + 1] - keyStart[i])
    fun idsAt(i: Int): IntArray = ids.copyOfRange(idStart[i], idStart[i + 1])

    /** The word ids of a lookup key (C# `_lookups.TryGetValue`), or null. */
    fun lookup(key: String): IntArray? = keyIndex(key).takeIf { it >= 0 }?.let(::idsAt)

    fun containsKey(key: String): Boolean = keyIndex(key) >= 0

    private fun keyIndex(key: String): Int {
        var lo = 0
        var hi = keyCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareKey(mid, key)
            if (c < 0) lo = mid + 1 else if (c > 0) hi = mid - 1 else return mid
        }
        return -1
    }

    private fun compareKey(i: Int, key: String): Int {
        val start = keyStart[i]
        val len = keyStart[i + 1] - start
        val n = minOf(len, key.length)
        for (j in 0 until n) {
            val d = keyChars[start + j] - key[j]
            if (d != 0) return d
        }
        return len - key.length
    }

    // --- words ---

    private fun wordIndex(id: Int): Int = java.util.Arrays.binarySearch(wordIds, id).let { if (it < 0) -1 else it }

    fun hasWord(id: Int) = wordIndex(id) >= 0

    /** `_wordFrequencyRanks`: 1 = most frequent; null when the frequency list doesn't have the word. */
    fun wordFrequencyRank(id: Int): Int? = wordIndex(id).takeIf { it >= 0 && ranks[it] > 0 }?.let { ranks[it] }

    fun isNameOnly(id: Int) = flag(id, NAME_ONLY)
    fun isExpression(id: Int) = flag(id, EXPRESSION)
    fun isKanjiBacked(id: Int) = flag(id, KANJI_BACKED)
    fun isTrueName(id: Int) = flag(id, TRUE_NAME)
    fun isFullyArchaic(id: Int) = flag(id, ARCHAIC)

    private fun flag(id: Int, bit: Int): Boolean {
        val i = wordIndex(id)
        return i >= 0 && (flags[i].toInt() and bit) != 0
    }

    /** `WordMeta.TryGetValue(id, out m) ? m.GetPriorityScore(isKana) : null`. */
    fun priorityScore(id: Int, isKana: Boolean): Int? {
        val i = wordIndex(id)
        if (i < 0) return null
        return if (isKana) priorityKana[i].toInt() else priorityKanji[i].toInt()
    }

    /** `Array.IndexOf(meta.Pos, pos) >= 0` (false for an unknown id). */
    fun hasPos(id: Int, pos: PartOfSpeech): Boolean {
        val i = wordIndex(id)
        if (i < 0) return false
        for (k in posStart[i] until posStart[i + 1]) if (tagPos[posTags[k].toInt()] == pos) return true
        return false
    }

    /** JmDictWordMeta.GetPrimaryPos, or null for an unknown id. */
    fun primaryPos(id: Int): PartOfSpeech? {
        val i = wordIndex(id)
        if (i < 0) return null
        for (k in posStart[i] until posStart[i + 1]) {
            val p = tagPos[posTags[k].toInt()]
            if (p != PartOfSpeech.Name && p != PartOfSpeech.Unknown) return p
        }
        return PartOfSpeech.Noun
    }

    /** Parser.WordMeta[id]; allocates, so hot paths use the accessors above. */
    fun wordMeta(id: Int): JmDictWordMeta? {
        val i = wordIndex(id)
        if (i < 0) return null
        val pos = Array(posStart[i + 1] - posStart[i]) { tagPos[posTags[posStart[i] + it].toInt()] }
        return JmDictWordMeta(pos, priorityKana[i], priorityKanji[i], origin(i), (flags[i].toInt() and TRUE_NAME) != 0)
    }

    private fun origin(i: Int) = if ((flags[i].toInt() and GAIRAIGO) != 0) WordOrigin.Gairaigo else WordOrigin.Unknown

    /** The word as JmDictCache serves it (forms, senses' tags and reading restrictions), or null. */
    fun getWord(id: Int): JmDictWord? {
        val i = wordIndex(id)
        if (i < 0) return null
        val pos = (posStart[i] until posStart[i + 1]).map { tags[posTags[it].toInt()] }
        val pri = (priStart[i] until priStart[i + 1]).map { tags[priTags[it].toInt()] }
        val record = String(blob, blobStart[i], blobStart[i + 1] - blobStart[i])
        val tab = record.indexOf('\t')
        val forms = record.substring(0, tab).split(';').mapIndexed { index, f ->
            val type = if (f[0] == 'K') JmDictFormType.KanjiForm else JmDictFormType.KanaForm
            val parts = f.substring(1).split(':')
            JmDictWordForm(id, index, parts[0], type, parts.getOrNull(1)?.let(::tagList), parts.getOrNull(2)?.let(::tagList),
                parts.getOrNull(3) == "n")
        }
        val senses = record.substring(tab + 1).let { s ->
            if (s.isEmpty()) listOf(JmDictDefinition(pos, null))
            else s.split(';').map { sense ->
                val at = sense.indexOf('@')
                if (at < 0) JmDictDefinition(tagList(sense) ?: emptyList(), null)
                else JmDictDefinition(tagList(sense.substring(0, at)) ?: emptyList(), sense.substring(at + 1).split(',').map(String::toInt))
            }
        }
        return JmDictWord(id, pos, pri.ifEmpty { null }, origin(i), forms, senses, (flags[i].toInt() and ARCHAIC) != 0)
    }

    private fun tagList(s: String): List<String>? = if (s.isEmpty()) null else s.split(',')

    // --- Parser.cs lookup helpers ---

    /** Tiers aren't interchangeable; each keeps the exact conversion its call sites rely on. */
    enum class KanaFallback { None, ToNormalizedHiragana, ToHiraganaKeepLongVowelMark, NormalizeToHiragana }

    fun hasLookupWhere(text: String, fallback: KanaFallback, match: (IntArray) -> Boolean): Boolean {
        lookup(text)?.let { if (match(it)) return true }
        val hira = try {
            when (fallback) {
                KanaFallback.ToHiraganaKeepLongVowelMark -> KanaConverter.toHiragana(text, convertLongVowelMark = false)
                KanaFallback.ToNormalizedHiragana -> KanaConverter.toNormalizedHiragana(text)
                KanaFallback.NormalizeToHiragana -> KanaNormalizer.normalize(KanaConverter.toHiragana(text))
                KanaFallback.None -> return false
            }
        } catch (e: RuntimeException) {
            return false
        }
        return hira != text && lookup(hira)?.let(match) == true
    }

    fun lookupWithKanaFallback(text: String, query: (String) -> Int?): Int? {
        query(text)?.let { return it }
        return try {
            val hira = KanaConverter.toNormalizedHiragana(text)
            if (hira != text) query(hira) else null
        } catch (e: RuntimeException) {
            null
        }
    }

    fun anyWithPos(ids: IntArray, pos: PartOfSpeech) = ids.any { hasPos(it, pos) }

    fun anyNonNameWithPos(ids: IntArray, pos: PartOfSpeech) = ids.any { !isNameOnly(it) && hasPos(it, pos) }

    /** uk or kanji-less words; kanji-backed non-uk words (遺影) and name entries (ヒューン) reject. */
    fun isKanaAppropriateId(id: Int): Boolean {
        val i = wordIndex(id)
        if (i < 0) return false
        val f = flags[i].toInt()
        if ((f and NAME_ONLY) != 0 || (f and TRUE_NAME) != 0) return false
        // uk is folded into the priority scores at load: kana = base + 10, kanji = base - 10
        val isUsuallyKana = priorityKana[i] > priorityKanji[i]
        return isUsuallyKana || (f and KANJI_BACKED) == 0
    }

    // --- the MorphologicalAnalyser callbacks (Parser.cs) ---

    fun hasLookupForCompound(dictForm: String) = hasLookupWhere(dictForm, KanaFallback.NormalizeToHiragana) { it.isNotEmpty() }

    fun hasNonNameLookup(text: String) =
        hasLookupWhere(text, KanaFallback.ToNormalizedHiragana) { ids -> ids.any { !isNameOnly(it) } }

    fun hasPrioritizedNonNameLookup(text: String) = hasLookupWhere(text, KanaFallback.ToNormalizedHiragana) { ids ->
        ids.any { !isNameOnly(it) && (priorityScore(it, true) ?: 0) > 0 }
    }

    fun hasKanaAppropriateLookup(text: String) =
        hasLookupWhere(text, KanaFallback.ToNormalizedHiragana) { ids -> ids.any(::isKanaAppropriateId) }

    /** Whether a noun+する merge resolves through deconjugation (密着 yes, 大怪我 no). */
    fun hasSuruVerbLookup(text: String) =
        hasLookupWhere(text, KanaFallback.None) { anyNonNameWithPos(it, PartOfSpeech.Verb) }

    /** Pins synthesised tokens (collapsed ごろごろごろ) to an entry without re-segmentation. */
    fun getNonNameCompoundId(text: String): Int? = lookupWithKanaFallback(text) { key ->
        val ids = lookup(key)
        if (ids == null || ids.isEmpty()) return@lookupWithKanaFallback null
        var bestId: Int? = null
        var bestRank = Long.MAX_VALUE
        for (id in ids) {
            if (isNameOnly(id)) continue
            val rank = (wordFrequencyRank(id) ?: Int.MAX_VALUE).toLong()
            // Ties break to the lowest WordId so the pin doesn't depend on lookup order
            if (bestId == null || rank < bestRank || (rank == bestRank && id < bestId)) {
                bestId = id
                bestRank = rank
            }
        }
        bestId
    }

    fun getBestNonNameFrequencyRank(text: String): Int? = lookupWithKanaFallback(text) { key ->
        val ids = lookup(key)
        if (ids == null || ids.isEmpty()) return@lookupWithKanaFallback null
        var best: Int? = null
        for (id in ids) {
            if (isNameOnly(id)) continue
            val rank = wordFrequencyRank(id) ?: continue
            if (best == null || rank < best) best = rank
        }
        best
    }

    /** Separates a real conjugating lexeme from a homographic noun a deconjugation lands on (明日/あす). */
    fun hasVerbOrAdjectiveLookup(text: String) = hasLookupWhere(text, KanaFallback.None) {
        anyWithPos(it, PartOfSpeech.Verb) || anyWithPos(it, PartOfSpeech.IAdjective)
    }

    fun hasExpressionLookup(text: String) = hasLookupWhere(text, KanaFallback.None) { anyWithPos(it, PartOfSpeech.Expression) }

    /** Any counter sense (度, 発); looser than Parser's HasCounterLookup, as mora re-cuts only need it to exist. */
    fun hasCounterSenseAvailable(text: String) =
        hasLookupWhere(text, KanaFallback.None) { anyWithPos(it, PartOfSpeech.Counter) }

    companion object {
        private const val TRUE_NAME = 1
        private const val NAME_ONLY = 2
        private const val EXPRESSION = 4
        private const val KANJI_BACKED = 8
        private const val ARCHAIC = 16
        private const val GAIRAIGO = 32

        /** LoadNameOnlyWordIds' SQL list: a word is name-only when every tag is one of these or name-*. */
        private val NAME_ONLY_TAGS = setOf("company", "given", "place", "person", "product", "ship", "surname",
            "unclass", "station", "group", "char", "creat", "dei", "doc", "ev", "fem", "fict", "leg", "masc", "myth",
            "obj", "organization", "oth", "relig", "serv", "work", "unc")

        /** Reads jiten-lexicon.tsv.gz (gzip or plain). */
        fun load(input: InputStream): JitenLexicon {
            val buffered = BufferedInputStream(input, 1 shl 16)
            buffered.mark(2)
            val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
            buffered.reset()
            val reader = (if (gzip) GZIPInputStream(buffered, 1 shl 16) else buffered).bufferedReader(Charsets.UTF_8)
            return reader.use { Loader().read(it) }
        }

        /** ParserRuntime.ComputePriorityBaseScore. */
        private fun priorityBaseScore(priorities: List<String>, hasName: Boolean): Int {
            var score = 0
            for (p in priorities) {
                when (p) {
                    "jiten" -> score += 100
                    "ichi1" -> score += 20
                    "ichi2" -> score += 10
                    "news1" -> score += 15
                    "news2" -> score += 10
                    "gai1" -> score += 15
                    "gai2" -> score += 10
                    else -> if (p.length > 2 && p[0] == 'n' && p[1] == 'f')
                        p.substring(2).toIntOrNull()?.let { score += JmDictWord.nfScore(it) }
                }
            }
            if (score == 0) for (p in priorities) {
                if (p == "spec1") score += 15
                if (p == "spec2") score += 5
            }
            if (hasName) score -= 50
            return score
        }

        private fun hasFrequencyMarker(priorities: List<String>) = priorities.any {
            it == "ichi1" || it == "ichi2" || it == "news1" || it == "news2" || it == "gai1" || it == "gai2" ||
                it == "spec1" || it == "spec2" || (it.length > 2 && it[0] == 'n' && it[1] == 'f')
        }
    }

    private class Loader {
        private val tagIds = HashMap<String, Short>()
        private val tagList = ArrayList<String>()

        private fun tag(s: String): Short = tagIds.getOrPut(s) {
            tagList.add(s)
            check(tagList.size < Short.MAX_VALUE) { "too many tags" }
            (tagList.size - 1).toShort()
        }

        fun read(reader: java.io.BufferedReader): JitenLexicon {
            var nWords = 0
            var nKeys = 0
            var line = reader.readLine()
            while (line != null && line.startsWith("#")) {
                if (line.startsWith("#words ")) line.split(' ').let { nWords = it[1].toInt(); nKeys = it[3].toInt() }
                line = reader.readLine()
            }
            require(nWords > 0) { "not a jiten lexicon (no #words header)" }

            val wordIds = IntArray(nWords)
            val posStart = IntArray(nWords + 1)
            val priStart = IntArray(nWords + 1)
            val posTags = ShortBuf()
            val priTags = ShortBuf()
            val priorityKana = ShortArray(nWords)
            val priorityKanji = ShortArray(nWords)
            val flags = ByteArray(nWords)
            val ranks = IntArray(nWords)
            val blobStart = IntArray(nWords + 1)
            val blob = CharBuf()
            var w = 0
            while (line != null && line.startsWith("W\t")) {
                val f = line.split('\t')
                wordIds[w] = f[1].toInt()
                val pos = if (f[2].isEmpty()) emptyList() else f[2].split(',')
                val pri = if (f[3].isEmpty()) emptyList() else f[3].split(',')
                pos.forEach { posTags.add(tag(it)) }
                pri.forEach { priTags.add(tag(it)) }
                posStart[w + 1] = posTags.size
                priStart[w + 1] = priTags.size

                // ParserRuntime.BuildWordMetadata
                var hasUk = false
                var hasName = false
                var hasTrueName = false
                for (p in pos) {
                    if (p == "uk") hasUk = true
                    if (p.toPartOfSpeech() == PartOfSpeech.Name) {
                        hasName = true
                        if (p != "unclass") hasTrueName = true
                    }
                }
                val base = priorityBaseScore(pri, hasName)
                val ukDelta = if (hasUk) 10 else 0
                var kana = base + ukDelta
                var kanji = base - ukDelta
                val archaic = 'a' in f[4]
                if (archaic && !hasFrequencyMarker(pri)) {
                    kana -= 350
                    kanji -= 350
                }
                priorityKana[w] = kana.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                priorityKanji[w] = kanji.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                var fl = 0
                if (hasTrueName) fl = fl or TRUE_NAME
                if (pos.isNotEmpty() && pos.all { it.startsWith("name-") || it in NAME_ONLY_TAGS }) fl = fl or NAME_ONLY
                if ("exp" in pos) fl = fl or EXPRESSION
                if (archaic) fl = fl or ARCHAIC
                if ('g' in f[4]) fl = fl or GAIRAIGO
                flags[w] = fl.toByte()
                ranks[w] = f[5].toIntOrNull() ?: 0
                blob.add(f[6]); blob.add('\t'); blob.add(f[7])
                blobStart[w + 1] = blob.size
                w++
                line = reader.readLine()
            }
            require(w == nWords) { "lexicon has $w words, header says $nWords" }

            val keyStart = IntArray(nKeys + 1)
            val idStart = IntArray(nKeys + 1)
            val keyChars = CharBuf()
            val ids = IntBuf()
            var k = 0
            var maxLen = 0
            while (line != null) {
                if (line.startsWith("L\t")) {
                    val tab = line.indexOf('\t', 2)
                    keyChars.add(line, 2, tab)
                    maxLen = maxOf(maxLen, tab - 2)
                    // ParserRuntime.BuildKanjiBackedWordIds: words with a kanji form
                    val kanjiKey = (2 until tab).any { JapaneseTextHelper.isKanji(line[it]) }
                    var start = tab + 1
                    while (start < line.length) {
                        val end = line.indexOf(' ', start).let { if (it < 0) line.length else it }
                        val id = line.substring(start, end).toInt()
                        ids.add(id)
                        if (kanjiKey) {
                            val i = java.util.Arrays.binarySearch(wordIds, id)
                            if (i >= 0) flags[i] = (flags[i].toInt() or KANJI_BACKED).toByte()
                        }
                        start = end + 1
                    }
                    k++
                    keyStart[k] = keyChars.size
                    idStart[k] = ids.size
                }
                line = reader.readLine()
            }
            require(k == nKeys) { "lexicon has $k lookup keys, header says $nKeys" }

            return JitenLexicon(wordIds, posStart, posTags.trim(), priStart, priTags.trim(), priorityKana, priorityKanji,
                flags, ranks, blobStart, blob.trim(), tagList.toTypedArray(), tagList.map { it.toPartOfSpeech() }.toTypedArray(),
                keyStart, keyChars.trim(), idStart, ids.trim(), maxLen)
        }
    }

    private class IntBuf {
        var a = IntArray(1 shl 16); var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun trim() = a.copyOf(size)
    }

    private class ShortBuf {
        var a = ShortArray(1 shl 16); var size = 0
        fun add(v: Short) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun trim() = a.copyOf(size)
    }

    private class CharBuf {
        var a = CharArray(1 shl 16); var size = 0
        fun add(c: Char) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = c }
        fun add(s: String, from: Int = 0, to: Int = s.length) {
            val n = to - from
            if (size + n > a.size) a = a.copyOf(maxOf(a.size * 2, size + n))
            s.toCharArray(a, size, from, to)
            size += n
        }
        fun trim() = a.copyOf(size)
    }
}
