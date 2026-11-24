// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Misparse/MisparseGates.cs
package chimahon.lang.jiten

import chimahon.lang.jiten.PartOfSpeech as P

internal data class MisparseDecision(val isMisparsed: Boolean = false, val gateId: String? = null)

/**
 * C# holds the selected DeckWord; the gates read only its WordId, OriginalText, PartsOfSpeech and Conjugations, so
 * those are the selected* fields (the secondary constructor takes the DeckWord, as C# does).
 */
internal data class MisparseGateContext(
    val token: WordInfo,
    val selectedWordId: Int,
    val selectedOriginalText: String,
    val selectedPartsOfSpeech: List<P>,
    val selectedConjugations: List<String>,
    val prev: WordInfo?,
    val next: WordInfo?,
    val isUsuallyKana: Boolean,
    val hasKanjiSpelling: Boolean,
    val readingIsIchi: Boolean,
    val isSentenceInitial: Boolean = false,
    val symbolsBefore: String = "",
    val symbolsAfter: String = "",
    val surfaceAttestsListedForm: Boolean = false,
    val surfaceAttestsLiterally: Boolean = false,
    val shardBlobUnattested: Boolean = false,
    val prevDroppedByGate: Boolean = false,
    val nextDroppedByGate: Boolean = false,
) {
    /** C#'s shape: the selected DeckWord. */
    constructor(
        token: WordInfo, selected: DeckWord, prev: WordInfo?, next: WordInfo?, isUsuallyKana: Boolean,
        hasKanjiSpelling: Boolean, readingIsIchi: Boolean, isSentenceInitial: Boolean = false, symbolsBefore: String = "",
        symbolsAfter: String = "", surfaceAttestsListedForm: Boolean = false, surfaceAttestsLiterally: Boolean = false,
        shardBlobUnattested: Boolean = false, prevDroppedByGate: Boolean = false, nextDroppedByGate: Boolean = false,
    ) : this(token, selected.wordId, selected.originalText, selected.partsOfSpeech, selected.conjugations, prev, next,
        isUsuallyKana, hasKanjiSpelling, readingIsIchi, isSentenceInitial, symbolsBefore, symbolsAfter,
        surfaceAttestsListedForm, surfaceAttestsLiterally, shardBlobUnattested, prevDroppedByGate, nextDroppedByGate)
}

/** GetWordFlags' tuple; destructures in the same order. */
internal data class MisparseWordFlags(
    val isUsuallyKana: Boolean,
    val hasKanjiSpelling: Boolean,
    val readingIsIchi: Boolean,
    val surfaceAttestsForm: Boolean,
    val surfaceAttestsLiterally: Boolean,
)

internal object MisparseGates {
    private val ExemptFromKanaGate = setOf(P.Particle, P.Auxiliary, P.Conjunction, P.Adnominal, P.Pronoun)

    private const val TrailingStretchChars = "ー〜っッぁぃぅぇぉ"

    /** Superset pre-filter: gates judge only kana of 4 chars or fewer, or a single-kana run. */
    fun mayBeKanaFragment(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.length > 4) {
            for (i in 1 until s.length) if (s[i] != s[0]) return false
        }
        for (c in s) if (!(c in '぀'..'ヿ' || c in 'ｦ'..'ﾝ' || c == '〜')) return false
        return true
    }

    fun evaluate(ctx: MisparseGateContext): MisparseDecision {
        if (isShortKanaNameWithoutContext(ctx)) return MisparseDecision(true, "short-kana-name")
        if (isRepeatedKanaStuttering(ctx)) return MisparseDecision(true, "repeated-kana-stutter")
        if (isKanaStutterBeforeWord(ctx)) return MisparseDecision(true, "kana-stutter-before-word")
        if (isShortKanaTokenWithoutJustification(ctx)) return MisparseDecision(true, "short-kana-unjustified")
        if (isSfxMimeticFragment(ctx)) return MisparseDecision(true, "sfx-mimetic-fragment")
        return MisparseDecision()
    }

    // A hiragana small vowel is expressive (ぱぁん); sokuon and katakana smalls are real orthography (おっさん, ファン).
    private const val ExpressiveSmallVowels = "ぁぃぅぇぉ"
    private const val GapMimeticChars = "っッぁぃぅぇぉ"

    private fun String.containsAnyOf(chars: String) = any { it in chars }

    private fun WordInfo?.isParticleTo() = this != null && text == "と" && partOfSpeech == P.Particle

    /** Drops blob shards (ざ|くぅ) and isolated mimetic nouns (ぱぁん！); real non-noun analyses (うりゃ→売る) stay. */
    private fun isSfxMimeticFragment(ctx: MisparseGateContext): Boolean {
        val surface = if (ctx.selectedOriginalText.isNotEmpty()) ctx.selectedOriginalText else ctx.token.text

        // Reduplication (コクコク) is mimetic; 6-char ones (ギュウギュウ) arrive only via a shorter token's OriginalText.
        val reduplicated = (surface.length == 4 || surface.length == 6) &&
            surface.substring(0, surface.length / 2) == surface.substring(surface.length / 2)
        if (surface.isEmpty() || (surface.length > 4 && !reduplicated) || surface.length > 6 ||
            !JapaneseTextHelper.isAllKana(surface)) return false

        // A dropped or unresolved kana shard neighbour is the same burst, so that side counts as framed.
        val prevShard = isKanaShardNeighbour(ctx.prev) || ctx.prevDroppedByGate
        val nextShard = isKanaShardNeighbour(ctx.next) || ctx.nextDroppedByGate
        // Sokuon only: a trailing ー is ordinary colloquial elongation of a real word (おまえー).
        val exclamatoryClip = ctx.symbolsAfter.isNotEmpty() && (ctx.symbolsAfter[0] == 'っ' || ctx.symbolsAfter[0] == 'ッ')
        // A clipped sokuon after a kana scrap (ず|がんっ) overrides usually-kana (癌); adjacency alone doesn't (わーい).
        val burstContext = exclamatoryClip &&
            (prevShard || (ctx.prev != null && ctx.prev.text.length <= 2 && JapaneseTextHelper.isAllKana(ctx.prev.text)))

        // A reduplication heading 〜と+verb (コクコクと振る) is isolated by the construction itself.
        val reduplicatedToFrame = reduplicated && ctx.next.isParticleTo()

        // Both sides must be cut off; a case particle or content word next to it is real syntax.
        val frameBefore = ctx.prev == null || ctx.symbolsBefore.isNotEmpty() || ctx.isSentenceInitial ||
            ctx.prev.partOfSpeech == P.Interjection ||
            (ctx.prev.partOfSpeech == P.Particle && ctx.prev.text in setOf("な", "よ", "ね", "ぞ", "ぜ", "わ", "さ")) ||
            (exclamatoryClip && ctx.prev.text.length <= 2 && JapaneseTextHelper.isAllKana(ctx.prev.text)) ||
            prevShard ||
            reduplicatedToFrame
        if (!frameBefore) return false

        val nextIsQuotativeTo = ctx.next.isParticleTo() && ctx.symbolsAfter.isNotEmpty()
        val frameAfter = ctx.next == null || ctx.symbolsAfter.isNotEmpty() || nextShard || reduplicatedToFrame
        if (!frameAfter) return false

        // Isolation alone fits any one-word answer (「梨」); ！ counts only if unattested (きゃ！→毛, not だめ！) or a vowel tail.
        val marker = surface.containsAnyOf(ExpressiveSmallVowels) ||
            ctx.symbolsAfter.containsAnyOf(GapMimeticChars) ||
            nextIsQuotativeTo ||
            prevShard || nextShard ||
            reduplicated ||
            // In-surface sokuon is phonetic unless the entry spells it (ズクッ→木菟, not バカッ); names exempt.
            (!ctx.surfaceAttestsListedForm &&
                (ctx.selectedWordId < 5000000 || ctx.selectedWordId >= 8000000) &&
                surface.containsAnyOf("っッ")) ||
            (surface.length <= 2 &&
                (!ctx.surfaceAttestsListedForm ||
                    (surface.length == 1 && surface[0] in "あいうえお" && ctx.next == null)) &&
                ctx.symbolsAfter.containsAnyOf("！？!?"))
        if (!marker) return false

        // A blob shard is phonetic whatever it matched (ばぁ→婆); only literal attestation counts (ざ|くぅ, not くう).
        // Adverb included: mimetic adverbs (おどおど) are kana-only entries without a uk tag.
        val blobInterjection = ctx.selectedPartsOfSpeech.any {
            it == P.Interjection || it == P.Expression || it == P.Adverb || it == P.AdverbTo
        }
        if (ctx.shardBlobUnattested &&
            !(blobInterjection && (ctx.isUsuallyKana || ctx.surfaceAttestsLiterally)) &&
            !(ctx.isUsuallyKana && ctx.surfaceAttestsLiterally))
            return true

        // Outside blobs only noun homographs are in scope (パン, 癌); real non-noun analyses (やめて！) stay, mutations (うぅ→うん) don't.
        // An adverbial sense in the と frame wins over the first-listed POS (ぽつぽつと灯す).
        val adverbialInToFrame = ctx.next.isParticleTo() &&
            ctx.selectedPartsOfSpeech.any { it == P.Adverb || it == P.AdverbTo }
        if ((ctx.selectedPartsOfSpeech.isEmpty() ||
                ctx.selectedPartsOfSpeech[0] !in setOf(P.Noun, P.CommonNoun, P.Numeral, P.NaAdjective) ||
                adverbialInToFrame) &&
            (ctx.surfaceAttestsListedForm || ctx.isUsuallyKana ||
                (ctx.selectedConjugations.isNotEmpty() && ctx.selectedConjugations[0] !in StemLikeConjugations)))
            return false

        // Bare-uttered nouns stay: spelled usually-kana vocatives (ばかっ！, not ズクッ) and exclamatory senses (嘘っ！).
        if (ctx.isUsuallyKana && ctx.surfaceAttestsListedForm && !burstContext) return false
        if (ctx.surfaceAttestsListedForm && ctx.selectedPartsOfSpeech.any { it == P.Interjection || it == P.Expression })
            return false

        // Plain nouns are never written with a clipped sokuon (とうっ！) or a punctuated quotative (ぶん！と).
        val punctuatedQuotative = nextIsQuotativeTo && ctx.symbolsAfter.containsAnyOf("！？、。…!?")
        if (exclamatoryClip || punctuatedQuotative) return true

        if (ctx.surfaceAttestsListedForm) {
            // A single kana spelled like a kanji word (ぶ→部) is that word only when case-marked.
            val anchored = surface.length >= 2 || (ctx.next != null && isGrammaticalFollower(ctx.next.text))
            if (anchored && ctx.readingIsIchi) return false
        }

        return true
    }

    private val StemLikeConjugations = setOf(
        "(stem)", "(infinitive)", "(unstressed infinitive)", "provisional conditional", "conjunctive",
        "(izenkei)", "(mizenkei)", "contracted",
    )

    fun isKanaShardNeighbour(w: WordInfo?): Boolean =
        w != null && w.resolvedWordId == null && w.text.length <= 3 && JapaneseTextHelper.isAllKana(w.text) &&
            w.partOfSpeech != P.Particle && w.partOfSpeech != P.Auxiliary && w.partOfSpeech != P.BlankSpace

    private fun isRepeatedKanaStuttering(ctx: MisparseGateContext): Boolean {
        val surface = ctx.token.text
        if (surface.length < 2 || !JapaneseTextHelper.isAllKana(surface)) return false

        val first = surface[0]
        for (i in 1 until surface.length) if (surface[i] != first) return false

        // Neighbour heuristics over-fire on repeated-vowel interjections (ああ before あたし); stutter shreds are Noun.
        if (ctx.token.partOfSpeech == P.Interjection) return false

        // Common vocabulary (パパ, もも).
        if (ctx.readingIsIchi || ctx.isUsuallyKana) return false

        val katakanaChar = if (first in 'ぁ'..'ん') first + 0x60 else first // hiragana → katakana

        if (ctx.prev != null && ctx.prev.text.indexOf(first) >= 0) return true

        // ぼぼ僕: Next.Reading is ボク.
        val reading = ctx.next?.reading
        if (!reading.isNullOrEmpty() && reading[0] == katakanaChar) return true

        // Onomatopoeia context (ちゅぼぼっ).
        if (ctx.prev != null && ctx.prev.text.length <= 2 && JapaneseTextHelper.isAllKana(ctx.prev.text) &&
            ctx.next != null && ctx.next.text.length <= 2 && JapaneseTextHelper.isAllKana(ctx.next.text))
            return true

        return false
    }

    private val StutterHostPos = setOf(
        P.Noun, P.Verb, P.IAdjective, P.NaAdjective, P.Adverb, P.Pronoun, P.Expression, P.Suffix, P.Counter,
        P.Numeral, P.Particle,
    )

    private fun isKanaStutterBeforeWord(ctx: MisparseGateContext): Boolean {
        val surface = ctx.token.text
        if (surface.length > 3 || !JapaneseTextHelper.isAllKana(surface)) return false

        // A repeated emphatic interjection (くそっくそっ) is deliberate; plain ones (はい) lack っ/ー and are de-duplicated.
        if (ctx.token.partOfSpeech == P.Interjection &&
            (surface.endsWith('っ') || surface.endsWith('ッ') || surface.endsWith('ー'))) return false

        if (ctx.readingIsIchi || ctx.isUsuallyKana) return false

        val next = ctx.next
        if (next == null || next.reading.isEmpty() || next.text.isEmpty()) return false

        // は + ハードル is not a stutter.
        if (next.text[0] in 'ァ'..'ヴ') return false

        // A particle after a real word is the particle (で before できる); real stutters follow punctuation or start.
        if (ctx.token.partOfSpeech == P.Particle && ctx.prev != null && ctx.prev.partOfSpeech in StutterHostPos)
            return false

        val katakana = JapaneseTextHelper.hiraganaToKatakana(surface)
        return next.reading.startsWith(katakana)
    }

    private fun isShortKanaNameWithoutContext(ctx: MisparseGateContext): Boolean {
        if (!JapaneseTextHelper.isAllKana(ctx.token.text)) return false
        if (ctx.token.text.length > 2) return false
        if (P.Name !in ctx.selectedPartsOfSpeech) return false
        if (ctx.token.isPersonNameContext) return false
        if (JapaneseTextHelper.isAllKatakana(ctx.token.text)) return false
        return true
    }

    private fun isShortKanaTokenWithoutJustification(ctx: MisparseGateContext): Boolean {
        val surface = ctx.token.text

        if (!JapaneseTextHelper.isAllKana(surface)) return false
        if (surface.length > 2) return false

        if (ctx.token.partOfSpeech in ExemptFromKanaGate) return false

        // A stem the deconjugator reassembled with its auxiliary (き|た) is grammar, not a stray fragment.
        if (ctx.token.isMergedInflection && ctx.selectedConjugations.isNotEmpty()) return false

        // Isolated two-kana interjections (ええ、) are utterances despite 嗚呼; shreds after a content word (いきた+ああ) stay gated.
        if (ctx.token.partOfSpeech == P.Interjection && surface.length >= 2 &&
            (ctx.isSentenceInitial || ctx.prev == null ||
                ctx.prev.partOfSpeech in setOf(P.SupplementarySymbol, P.Symbol, P.BlankSpace, P.Interjection))) return false

        // ああ/こう/そう before a verb (ああなった) is the demonstrative adverb; shreds never precede a verb.
        if ((surface == "ああ" || surface == "こう" || surface == "そう") && ctx.next?.partOfSpeech == P.Verb)
            return false

        if (ctx.isUsuallyKana) return false
        if (!ctx.hasKanjiSpelling) return false
        if (ctx.readingIsIchi) return false
        if (JapaneseTextHelper.isAllKatakana(surface)) return false
        if (ctx.next != null && isGrammaticalFollower(ctx.next.text)) return false

        return true
    }

    private val GrammaticalFollowers = setOf(
        "が", "を", "に", "は", "の", "で", "と", "へ", "から", "まで", "より", "も", "って", "だ", "です",
    )

    private fun isGrammaticalFollower(text: String): Boolean =
        text in GrammaticalFollowers ||
            // って-clusters (っていう, ってのは) justify a quoted short-kana verb like bare って (してある+っていう).
            text.startsWith("って")

    fun getWordFlags(word: JmDictWord?, readingIndex: Int, surface: String? = null): MisparseWordFlags {
        if (word == null) return MisparseWordFlags(false, true, false, false, false)

        val isUk = "uk" in word.partsOfSpeech
        var hasKanji = false
        var readingIsIchi = word.priorities?.contains("jiten") == true
        // Literal same-script match: ぱん doesn't attest パン; an unspelled surface came from normalisation or mutation.
        var surfaceAttestsForm = false
        for (f in word.forms) {
            if (f.formType == JmDictFormType.KanjiForm) hasKanji = true
            if (!readingIsIchi && f.formType == JmDictFormType.KanaForm && f.readingIndex == readingIndex &&
                f.priorities.let { p -> p != null && ("ichi1" in p || "ichi2" in p || "jiten" in p) })
                readingIsIchi = true
            if (surface != null && f.text == surface) surfaceAttestsForm = true
        }

        var surfaceAttestsLiterally = surfaceAttestsForm

        // Mimetics take either kana script (スタスタと) but JMdict lists hiragana; class-limited so SFX can't claim other words.
        if (!surfaceAttestsForm && !surface.isNullOrEmpty() &&
            word.partsOfSpeech.any { it == "adv" || it == "adv-to" || it == "int" || it == "on-mim" } &&
            JapaneseTextHelper.isAllKatakana(surface)) {
            val folded = KanaConverter.toHiragana(surface)
            if (folded != surface && word.forms.any { it.text == folded }) {
                surfaceAttestsForm = true
                surfaceAttestsLiterally = true
            }
        }

        // Expressive spelling (ほんっと, おーっと, そっかー) attests kana-native or priority words; on rare kanji it means mutation (ズクッ→木菟).
        if (!surfaceAttestsForm && !surface.isNullOrEmpty() && (!hasKanji || !word.priorities.isNullOrEmpty())) {
            var detrailed = surface.trimEnd { it in TrailingStretchChars }
            val degeminated = surface.filter { it != 'っ' && it != 'ッ' }
            // Function words are unstressed: de-stretching わーい to the particle わい is noise.
            val stretchable = !word.priorities.isNullOrEmpty() || word.partsOfSpeech.any { it == "int" || it == "exp" }
            val destretched = if (stretchable) surface.filter { it != 'ー' && it != '〜' } else surface
            var bare = if (stretchable) detrailed.filter { it !in "っッー〜" } else detrailed
            // The strip must not dominate: そっかー is そっか, but ああぁぁーー is scream material.
            if (detrailed.length * 2 < surface.length) detrailed = surface
            if (bare.length * 2 < surface.length) bare = surface
            // A trailing small vowel stands for the full vowel (ふぅ→ふう), except in vowel-initial screams (うぅ).
            var promoted = surface
            if (surface[0] !in "あいうえおぁぃぅぇぉアイウエオァィゥェォ") {
                var end = surface.length
                while (end > 0 && surface[end - 1] in "ぁぃぅぇぉァィゥェォ") end--
                if (end < surface.length) {
                    val chars = surface.toCharArray()
                    for (i in end until surface.length)
                        chars[i] = when (chars[i]) {
                            'ぁ' -> 'あ'; 'ぃ' -> 'い'; 'ぅ' -> 'う'; 'ぇ' -> 'え'; 'ぉ' -> 'お'
                            'ァ' -> 'ア'; 'ィ' -> 'イ'; 'ゥ' -> 'ウ'; 'ェ' -> 'エ'; 'ォ' -> 'オ'
                            else -> chars[i]
                        }
                    promoted = String(chars)
                }
            }
            for (variant in arrayOf(detrailed, degeminated, destretched, bare, promoted)) {
                if (variant.isEmpty() || variant == surface) continue
                if (word.forms.any { it.text == variant }) {
                    surfaceAttestsForm = true
                    break
                }
            }
        }

        return MisparseWordFlags(isUk, hasKanji, readingIsIchi, surfaceAttestsForm, surfaceAttestsLiterally)
    }
}
