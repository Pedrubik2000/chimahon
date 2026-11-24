// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Core/Data/PosMapper.cs
package chimahon.lang.jiten

/** POS conversions between Sudachi, JMdict tags and deconjugator tags. Tag sets compare case-insensitively, as in C#. */
object PosMapper {
    /** Bases an inflection can start from (MorphologicalAnalyser combining). */
    val InflectableBasePOS: Set<PartOfSpeech> = setOf(PartOfSpeech.Verb, PartOfSpeech.IAdjective, PartOfSpeech.NaAdjective)

    /** Parts that attach to an inflectable base. */
    val InflectionPartPOS: Set<PartOfSpeech> = setOf(PartOfSpeech.Auxiliary, PartOfSpeech.Suffix, PartOfSpeech.Particle)

    /** POS valid for compound expressions (Parser compound validation). */
    val ValidCompoundPOS: Set<PartOfSpeech> = setOf(PartOfSpeech.Expression, PartOfSpeech.Verb, PartOfSpeech.IAdjective,
        PartOfSpeech.NaAdjective, PartOfSpeech.Auxiliary, PartOfSpeech.Conjunction)

    /** POS that can take part in noun compounding. */
    val NounCompoundPOS: Set<PartOfSpeech> = setOf(PartOfSpeech.Noun, PartOfSpeech.Name, PartOfSpeech.CommonNoun,
        PartOfSpeech.NaAdjective, PartOfSpeech.Numeral, PartOfSpeech.Prefix, PartOfSpeech.Suffix)

    /** Deconjugator tags whose matched word must have a compatible JMdict POS (stored lower-case). */
    val DeconjTagsRequiringValidation: Set<String> = setOf(
        "v1", "v1-s", "vs-s", "vs-i", "vs-c", "vk",
        "v5aru", "v5b", "v5g", "v5k", "v5k-s", "v5m", "v5n", "v5r", "v5r-i", "v5s", "v5t", "v5u", "v5u-s", "v5uru",
        "v4r", "adj-i", "adj-na", "adj-ix", "aux",
        "stem-past", "stem-te", "stem-te-defective", "stem-te-verbal", "stem-ren", "stem-ren-less", "stem-ren-less-v",
    )

    // Directly-conjugating verbs only: suru-verbs need する before た/て, so generic stem tags must not match vs/vt/vi.
    private val DirectConjugationVerbTags: Set<String> = setOf(
        "v1", "v1-s", "v5aru", "v5b", "v5g", "v5k", "v5k-s", "v5m", "v5n",
        "v5r", "v5r-i", "v5s", "v5t", "v5u", "v5u-s", "v5uru",
        "vk", "vz",
        "v4k", "v4g", "v4s", "v4t", "v4n", "v4b", "v4m", "v4r", "v4h",
        "v2a-s", "v2b-k", "v2d-s", "v2g-k", "v2g-s", "v2h-k", "v2h-s",
        "v2k-k", "v2k-s", "v2m-k", "v2m-s", "v2n-s", "v2r-k", "v2r-s",
        "v2s-s", "v2t-k", "v2t-s", "v2w-s", "v2y-k", "v2y-s", "v2z-s",
        "vn", "vr", "aux-v",
    )

    /** Deconjugator tag (lower-case key) -> compatible JMdict tags. */
    private val DeconjToJmDictCompatibility: MutableMap<String, MutableSet<String>> = hashMapOf(
        "vs-i" to mutableSetOf("vs-i", "vs", "vs-s"),
        "vs-s" to mutableSetOf("vs-s", "vs", "vs-i"),
        "vs-c" to mutableSetOf("vs-c", "vs"),
        "v1-s" to mutableSetOf("v1-s", "v1"),
        // exp too: many expressions conjugate like i-adjectives (かもしれない, しかたがない)
        "adj-i" to mutableSetOf("adj-i", "exp"),
        "adj-ix" to mutableSetOf("adj-ix", "adj-i"),
        "v5r-i" to mutableSetOf("v5r-i", "v5r"),
        "v5k-s" to mutableSetOf("v5k-s", "v5k"),
        "v5u-s" to mutableSetOf("v5u-s", "v5u"),
        // Yodan-ru inflects like godan-ru (癒り → 治る), never like ichidan
        "v4r" to mutableSetOf("v4r", "v5r", "v5r-i", "v5aru"),
        "stem-past" to DirectConjugationVerbTags.toMutableSet(),
        "stem-te" to DirectConjugationVerbTags.toMutableSet(),
        "stem-te-defective" to DirectConjugationVerbTags.toMutableSet(),
        "stem-te-verbal" to DirectConjugationVerbTags.toMutableSet(),
        // ます attaches to a 連用形: a noun or expression spelled like the stem (ござい) cannot take it
        "stem-ren" to DirectConjugationVerbTags.toMutableSet(),
        "stem-ren-less" to DirectConjugationVerbTags.toMutableSet(),
        "stem-ren-less-v" to DirectConjugationVerbTags.toMutableSet(),
    )

    fun fromSudachi(sudachiPos: String): PartOfSpeech = when (sudachiPos) {
        "名詞" -> PartOfSpeech.Noun
        "動詞" -> PartOfSpeech.Verb
        "形容詞" -> PartOfSpeech.IAdjective
        "形状詞" -> PartOfSpeech.NaAdjective
        "副詞" -> PartOfSpeech.Adverb
        "助詞" -> PartOfSpeech.Particle
        "接続詞" -> PartOfSpeech.Conjunction
        "助動詞" -> PartOfSpeech.Auxiliary
        "感動詞" -> PartOfSpeech.Interjection
        "記号" -> PartOfSpeech.Symbol
        "接頭詞", "接頭辞" -> PartOfSpeech.Prefix
        "フィラー" -> PartOfSpeech.Filler
        "代名詞" -> PartOfSpeech.Pronoun
        "接尾辞" -> PartOfSpeech.Suffix
        "普通名詞" -> PartOfSpeech.CommonNoun
        "補助記号" -> PartOfSpeech.SupplementarySymbol
        "空白" -> PartOfSpeech.BlankSpace
        "表現" -> PartOfSpeech.Expression
        "形動" -> PartOfSpeech.NominalAdjective
        "連体詞" -> PartOfSpeech.PrenounAdjectival
        "数詞" -> PartOfSpeech.Numeral
        "助数詞" -> PartOfSpeech.Counter
        "副詞的と" -> PartOfSpeech.AdverbTo
        "名詞接尾辞" -> PartOfSpeech.NounSuffix
        else -> PartOfSpeech.Unknown
    }

    fun fromJmDict(jmDictTag: String): PartOfSpeech {
        if (jmDictTag.isNotEmpty() && jmDictTag[0] == 'v' && jmDictTag != "vulg" && jmDictTag != "vet" && jmDictTag != "vidg")
            return PartOfSpeech.Verb
        // JMnedict name-* tags (name-fem, name-masc) are names too
        if (jmDictTag.startsWith("name-")) return PartOfSpeech.Name
        return when (jmDictTag) {
            "n", "n-adv", "n-t", "n-pr" -> PartOfSpeech.Noun
            "adj-i", "adj-ix" -> PartOfSpeech.IAdjective
            "adj-na" -> PartOfSpeech.NaAdjective
            "adj-no", "adj-t", "adj-f" -> PartOfSpeech.NominalAdjective
            "adj-pn" -> PartOfSpeech.PrenounAdjectival
            "adv" -> PartOfSpeech.Adverb
            "adv-to" -> PartOfSpeech.AdverbTo
            "prt" -> PartOfSpeech.Particle
            "conj" -> PartOfSpeech.Conjunction
            "aux", "aux-v", "aux-adj" -> PartOfSpeech.Auxiliary
            "int" -> PartOfSpeech.Interjection
            "pref", "n-pref" -> PartOfSpeech.Prefix
            "suf" -> PartOfSpeech.Suffix
            "n-suf" -> PartOfSpeech.NounSuffix
            "pn" -> PartOfSpeech.Pronoun
            "exp" -> PartOfSpeech.Expression
            "num" -> PartOfSpeech.Numeral
            "ctr" -> PartOfSpeech.Counter
            "company", "given", "place", "person", "product", "ship", "surname", "unclass", "name-fem", "name-masc",
            "name-male", "station", "group", "char", "creat", "dei", "doc", "ev", "fict", "leg", "myth", "obj",
            "organization", "oth", "relig", "serv", "work", "unc" -> PartOfSpeech.Name
            else -> PartOfSpeech.Unknown
        }
    }

    /** Sudachi first (Japanese strings), then JMdict tags. */
    fun fromAny(pos: String): PartOfSpeech {
        val result = fromSudachi(pos)
        return if (result != PartOfSpeech.Unknown) result else fromJmDict(pos)
    }

    fun fromAny(posList: Iterable<String>): List<PartOfSpeech> = posList.map(::fromAny)

    /** Whether a JMdict word's POS fits a Sudachi-derived POS when matching tokens to entries. */
    fun isJmDictCompatibleWithSudachi(
        convertedPosList: List<PartOfSpeech>,
        sudachiPosIn: PartOfSpeech,
        allowInterjectionFallback: Boolean = false,
        allowNounExpressionFallback: Boolean = false,
    ): Boolean {
        // CommonNoun (orphaned suffixes reclassified by the analyser) uses Noun compatibility
        val sudachiPos = if (sudachiPosIn == PartOfSpeech.CommonNoun) PartOfSpeech.Noun else sudachiPosIn
        fun has(p: PartOfSpeech) = convertedPosList.contains(p)

        if (has(sudachiPos)) return true
        if (allowInterjectionFallback && has(PartOfSpeech.Interjection)) return true
        // Kana set phrases Sudachi bucketed as 固有名詞 (そうか): exp entries may compete there only
        if (allowNounExpressionFallback && sudachiPos == PartOfSpeech.Noun && has(PartOfSpeech.Expression)) return true
        // 形状詞 covers adj-pn words (この, こんな)
        if (sudachiPos == PartOfSpeech.NaAdjective && has(PartOfSpeech.PrenounAdjectival)) return true
        // classical attributive forms with adj-pn entries (亡き)
        if (sudachiPos == PartOfSpeech.IAdjective && has(PartOfSpeech.PrenounAdjectival)) return true
        // 連体詞 vs adj-f/adj-no/adj-t and adj-na (同じ)
        if (sudachiPos == PartOfSpeech.PrenounAdjectival && (has(PartOfSpeech.NominalAdjective) || has(PartOfSpeech.NaAdjective)))
            return true
        // 感動詞 set phrases tagged exp (初めまして)
        if (sudachiPos == PartOfSpeech.Interjection && has(PartOfSpeech.Expression)) return true
        // 代名詞 contractions tagged exp/adv/int (そりゃ, こりゃ)
        if (sudachiPos == PartOfSpeech.Pronoun &&
            (has(PartOfSpeech.Expression) || has(PartOfSpeech.Adverb) || has(PartOfSpeech.Interjection))) return true
        // 副詞 vs int (いや) and adv-to (轟々)
        if (sudachiPos == PartOfSpeech.Adverb && has(PartOfSpeech.Interjection)) return true
        if (sudachiPos == PartOfSpeech.Adverb && has(PartOfSpeech.AdverbTo)) return true
        // 名詞 vs adj-no (本当), adv (副詞可能 nouns) and num (CombineAmounts' 二つ)
        if (sudachiPos == PartOfSpeech.Noun && has(PartOfSpeech.NominalAdjective)) return true
        if (sudachiPos == PartOfSpeech.Noun && has(PartOfSpeech.Adverb)) return true
        if (sudachiPos == PartOfSpeech.Noun && has(PartOfSpeech.Numeral)) return true
        // 接尾辞 vs n-suf/suf; counters deliberately not (they need a numeral this check can't see)
        if (sudachiPos == PartOfSpeech.Suffix && (has(PartOfSpeech.NounSuffix) || has(PartOfSpeech.Suffix))) return true
        // verbal/adjectival set phrases tagged exp (いけない, しょうがない)
        if ((sudachiPos == PartOfSpeech.Verb || sudachiPos == PartOfSpeech.IAdjective) && has(PartOfSpeech.Expression)) return true
        // copulas: 助動詞 in Sudachi, prt/cop in JMdict
        if (sudachiPos == PartOfSpeech.Auxiliary && has(PartOfSpeech.Particle)) return true
        return false
    }

    fun isJmDictCompatibleWithSudachi(
        jmDictPosTags: Iterable<String>,
        sudachiPos: PartOfSpeech,
        allowInterjectionFallback: Boolean = false,
    ): Boolean = isJmDictCompatibleWithSudachi(jmDictPosTags.map(::fromJmDict), sudachiPos, allowInterjectionFallback)

    /** Sudachi names come as 名詞 with a name-like section (固有名詞, 人名, 地名...). */
    fun isNameLikeSudachiNoun(
        sudachiPos: PartOfSpeech,
        section1: PartOfSpeechSection,
        section2: PartOfSpeechSection,
        section3: PartOfSpeechSection,
    ): Boolean {
        if (sudachiPos != PartOfSpeech.Noun) return false
        return isNameLikeSudachiSection(section1) || isNameLikeSudachiSection(section2) || isNameLikeSudachiSection(section3)
    }

    private fun isNameLikeSudachiSection(section: PartOfSpeechSection) = when (section) {
        PartOfSpeechSection.ProperNoun, PartOfSpeechSection.PersonName, PartOfSpeechSection.FamilyName,
        PartOfSpeechSection.Organization, PartOfSpeechSection.PlaceName, PartOfSpeechSection.Region,
        PartOfSpeechSection.Country, PartOfSpeechSection.Name -> true
        else -> false
    }

    /** A deconjugator tag (v5g, vs-i) against a JMdict entry's tags, through the compatibility map (vs-i -> vs). */
    fun isDeconjTagCompatibleWithJmDict(deconjTag: String, jmDictPosTags: Iterable<String>): Boolean {
        val tags = jmDictPosTags as? Collection<String> ?: jmDictPosTags.toList()
        if (tags.any { it.equals(deconjTag, ignoreCase = true) }) return true
        val compatible = DeconjToJmDictCompatibility[deconjTag.lowercase()] ?: return false
        return compatible.any { c -> tags.any { it.equals(c, ignoreCase = true) } }
    }

    fun areDeconjTagsCompatibleWithJmDict(deconjTags: Iterable<String>, jmDictPosTags: Iterable<String>): Boolean {
        val tags = jmDictPosTags as? Collection<String> ?: jmDictPosTags.toList()
        return deconjTags.any { isDeconjTagCompatibleWithJmDict(it, tags) }
    }

    fun getValidatableDeconjTags(tags: Iterable<String>): List<String> =
        tags.filter { it.lowercase() in DeconjTagsRequiringValidation }

    fun isInflectableBase(pos: PartOfSpeech) = pos in InflectableBasePOS
    fun isInflectionPart(pos: PartOfSpeech) = pos in InflectionPartPOS
    fun isValidCompoundPOS(pos: PartOfSpeech) = pos in ValidCompoundPOS
    fun isNounForCompounding(pos: PartOfSpeech) = pos in NounCompoundPOS

    fun registerDeconjCompatibility(deconjTag: String, vararg compatibleJmDictTags: String) {
        DeconjToJmDictCompatibility.getOrPut(deconjTag.lowercase()) { mutableSetOf() }.addAll(compatibleJmDictTags)
    }
}
