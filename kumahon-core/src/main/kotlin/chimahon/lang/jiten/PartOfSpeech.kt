// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Core/Data/PartOfSpeech.cs
package chimahon.lang.jiten

/** Ordinals equal Jiten's enum values (PosMask bits depend on them). */
enum class PartOfSpeech {
    Unknown, Noun, Verb, IAdjective, Adverb, Particle, Conjunction, Auxiliary, Adnominal, Interjection, Symbol, Prefix,
    Filler, Name, Pronoun, NaAdjective, Suffix, CommonNoun, SupplementarySymbol, BlankSpace, Expression,
    NominalAdjective, Numeral, PrenounAdjectival, Counter, AdverbTo, NounSuffix,
}

/** C# uint masks as Int: bit n = PartOfSpeech ordinal n (27 bits). */
object PosMask {
    fun bit(pos: PartOfSpeech): Int = 1 shl pos.ordinal

    fun fromList(list: Iterable<PartOfSpeech>): Int {
        var mask = 0
        for (pos in list) mask = mask or (1 shl pos.ordinal)
        return mask
    }

    private fun bits(vararg p: PartOfSpeech) = fromList(p.asIterable())

    val NounLike = bits(PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.NaAdjective, PartOfSpeech.Pronoun,
        PartOfSpeech.Name, PartOfSpeech.NominalAdjective)
    val PredicateHost = bits(PartOfSpeech.Verb, PartOfSpeech.IAdjective, PartOfSpeech.Auxiliary)
    val AdverbGroup = bits(PartOfSpeech.Adverb, PartOfSpeech.AdverbTo)

    /** Content words that carry topic signal (no function words, symbols, numerals, affixes or names). */
    val ContentWord = bits(PartOfSpeech.Noun, PartOfSpeech.Verb, PartOfSpeech.IAdjective, PartOfSpeech.Adverb,
        PartOfSpeech.NaAdjective, PartOfSpeech.CommonNoun, PartOfSpeech.Expression, PartOfSpeech.NominalAdjective,
        PartOfSpeech.PrenounAdjectival, PartOfSpeech.Counter, PartOfSpeech.AdverbTo)
    val VerbOrIAdj = bits(PartOfSpeech.Verb, PartOfSpeech.IAdjective)
    val VerbAuxOrIAdj = bits(PartOfSpeech.Verb, PartOfSpeech.Auxiliary, PartOfSpeech.IAdjective)
    val NumericLike = bits(PartOfSpeech.Numeral, PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.Pronoun,
        PartOfSpeech.Name)
    val SuffixGroup = bits(PartOfSpeech.Suffix, PartOfSpeech.NounSuffix)

    val Particle = bits(PartOfSpeech.Particle)
    val Auxiliary = bits(PartOfSpeech.Auxiliary)
    val Verb = bits(PartOfSpeech.Verb)
    val IAdjective = bits(PartOfSpeech.IAdjective)
    val NaAdjective = bits(PartOfSpeech.NaAdjective)
    val Counter = bits(PartOfSpeech.Counter)
    val Numeral = bits(PartOfSpeech.Numeral)
    val AdverbTo = bits(PartOfSpeech.AdverbTo)
    val PrenounAdjectival = bits(PartOfSpeech.PrenounAdjectival)
    val Conjunction = bits(PartOfSpeech.Conjunction)
    val Interjection = bits(PartOfSpeech.Interjection)
    val NameBit = bits(PartOfSpeech.Name)

    fun has(mask: Int, bits: Int): Boolean = (mask and bits) != 0
}

/** Ordinals equal Jiten's enum values. */
enum class PartOfSpeechSection {
    None, Amount, Alphabet, FullStop, BlankSpace, Suffix, Pronoun, Independant, Dependant, Filler, Common,
    SentenceEndingParticle, Counter, ParallelMarker, BindingParticle, PotentialAdverb, CaseMarkingParticle,
    IrregularConjunction, ConjunctionParticle, AuxiliaryVerbStem, AdjectivalStem, CompoundWord, Quotation,
    NounConjunction, AdverbialParticle, ConjunctiveParticleClass, Adverbialization,
    AdverbialParticleOrParallelMarkerOrSentenceEndingParticle, AdnominalAdjective, ProperNoun, Special,
    VerbConjunction, PersonName, FamilyName, Organization, NotAdjectiveStem, Comma, OpeningBracket, ClosingBracket,
    Region, Country, Numeral, PossibleDependant, CommonNoun, SubstantiveAdjective, PossibleCounterWord, PossibleSuru,
    Juntaijoushi, PossibleNaAdjective, VerbLike, PossibleVerbSuruNoun, Adjectival, NaAdjectiveLike, Name, Letter,
    PlaceName, TaruAdjective,
}

// PartOfSpeechExtension

/** A Sudachi or JMdict POS string to [PartOfSpeech] ([PosMapper.fromAny]). */
fun String.toPartOfSpeech(): PartOfSpeech = PosMapper.fromAny(this)

fun List<String>.toPartOfSpeech(): List<PartOfSpeech> = map { it.toPartOfSpeech() }

fun List<String>.toPartOfSpeechSection(): List<PartOfSpeechSection> = map { it.toPartOfSpeechSection() }

fun String.toPartOfSpeechSection(): PartOfSpeechSection = when (this) {
    "*" -> PartOfSpeechSection.None
    "数" -> PartOfSpeechSection.Amount
    "アルファベット" -> PartOfSpeechSection.Alphabet
    "句点" -> PartOfSpeechSection.FullStop
    "空白" -> PartOfSpeechSection.BlankSpace
    "接尾", "suf" -> PartOfSpeechSection.Suffix
    "代名詞", "pn" -> PartOfSpeechSection.Pronoun
    "自立" -> PartOfSpeechSection.Independant
    "フィラー" -> PartOfSpeechSection.Filler
    "一般" -> PartOfSpeechSection.Common
    "非自立" -> PartOfSpeechSection.Dependant
    "終助詞" -> PartOfSpeechSection.SentenceEndingParticle
    "助数詞", "ctr" -> PartOfSpeechSection.Counter
    "並立助詞" -> PartOfSpeechSection.ParallelMarker
    "係助詞" -> PartOfSpeechSection.BindingParticle
    "副詞可能" -> PartOfSpeechSection.PotentialAdverb
    "格助詞" -> PartOfSpeechSection.CaseMarkingParticle
    "サ変接続" -> PartOfSpeechSection.IrregularConjunction
    "接続助詞" -> PartOfSpeechSection.ConjunctionParticle
    "助動詞語幹" -> PartOfSpeechSection.AuxiliaryVerbStem
    "形容動詞語幹" -> PartOfSpeechSection.AdjectivalStem
    "連語" -> PartOfSpeechSection.CompoundWord
    "引用" -> PartOfSpeechSection.Quotation
    "名詞接続" -> PartOfSpeechSection.NounConjunction
    "副助詞" -> PartOfSpeechSection.AdverbialParticle
    "助詞類接続" -> PartOfSpeechSection.ConjunctiveParticleClass
    "副詞化" -> PartOfSpeechSection.Adverbialization
    "副助詞／並立助詞／終助詞" -> PartOfSpeechSection.AdverbialParticleOrParallelMarkerOrSentenceEndingParticle
    "連体化" -> PartOfSpeechSection.AdnominalAdjective
    "固有名詞" -> PartOfSpeechSection.ProperNoun
    "特殊" -> PartOfSpeechSection.Special
    "動詞接続" -> PartOfSpeechSection.VerbConjunction
    "人名" -> PartOfSpeechSection.PersonName
    "姓" -> PartOfSpeechSection.FamilyName
    "組織" -> PartOfSpeechSection.Organization
    "ナイ形容詞語幹" -> PartOfSpeechSection.NotAdjectiveStem
    "読点" -> PartOfSpeechSection.Comma
    "括弧開" -> PartOfSpeechSection.OpeningBracket
    "括弧閉" -> PartOfSpeechSection.ClosingBracket
    "地域" -> PartOfSpeechSection.Region
    "国" -> PartOfSpeechSection.Country
    "数詞", "num" -> PartOfSpeechSection.Numeral
    "非自立可能" -> PartOfSpeechSection.PossibleDependant
    "普通名詞" -> PartOfSpeechSection.CommonNoun
    "名詞的" -> PartOfSpeechSection.SubstantiveAdjective
    "助数詞可能" -> PartOfSpeechSection.PossibleCounterWord
    "サ変可能" -> PartOfSpeechSection.PossibleSuru
    "準体助詞" -> PartOfSpeechSection.Juntaijoushi
    "形状詞可能" -> PartOfSpeechSection.PossibleNaAdjective
    "動詞的" -> PartOfSpeechSection.VerbLike
    "サ変形状詞可能" -> PartOfSpeechSection.PossibleVerbSuruNoun
    "形容詞的" -> PartOfSpeechSection.Adjectival
    "名", "company", "given", "place", "person", "product", "ship", "surname", "unclass", "name-fem", "name-masc",
    "station", "group", "char", "creat", "dei", "doc", "ev", "fem", "fict", "leg", "masc", "myth", "obj",
    "organization", "oth", "relig", "serv", "work", "unc" -> PartOfSpeechSection.Name
    "文字" -> PartOfSpeechSection.Letter
    "形状詞的" -> PartOfSpeechSection.NaAdjectiveLike
    "地名" -> PartOfSpeechSection.PlaceName
    "タリ" -> PartOfSpeechSection.TaruAdjective
    else -> PartOfSpeechSection.None
}
