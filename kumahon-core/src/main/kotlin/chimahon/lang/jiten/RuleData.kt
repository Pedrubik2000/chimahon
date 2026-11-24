// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Helpers/MorphologicalAnalyser.RuleData.cs
package chimahon.lang.jiten

// C#'s private static fields of the partial class, as internal top-level values (names kept; leading _ dropped).
// The tuple sets keep their insertion order (C# HashSet enumerates in insertion order; CombineStages builds its lookup
// lists from it).

internal data class SpecialCase3(val first: String, val second: String, val third: String, val pos: PartOfSpeech?)

internal data class SpecialCase2(val first: String, val second: String, val pos: PartOfSpeech?)

internal val SpecialCases3: Set<SpecialCase3> = linkedSetOf(
    SpecialCase3("な", "の", "で", PartOfSpeech.Expression),
    SpecialCase3("それ", "で", "も", PartOfSpeech.Conjunction),
    SpecialCase3("なく", "なっ", "た", PartOfSpeech.Verb),
    SpecialCase3("さ", "せ", "て", PartOfSpeech.Verb),
    SpecialCase3("ほう", "が", "いい", PartOfSpeech.Expression),
    SpecialCase3("に", "とっ", "て", PartOfSpeech.Expression),
    SpecialCase3("に", "つい", "て", PartOfSpeech.Expression),
    SpecialCase3("か", "の", "ように", PartOfSpeech.Expression),
    SpecialCase3("それ", "よ", "か", PartOfSpeech.Expression),
    SpecialCase3("に", "劣ら", "ず", PartOfSpeech.Expression),
    SpecialCase3("しょう", "が", "ねぇ", PartOfSpeech.IAdjective),
    SpecialCase3("しょう", "が", "ねー", PartOfSpeech.IAdjective),
    SpecialCase3("いけす", "か", "ねぇ", PartOfSpeech.IAdjective),
    SpecialCase3("いけす", "か", "ねー", PartOfSpeech.IAdjective),
    SpecialCase3("たら", "し", "たら", PartOfSpeech.Verb),
    SpecialCase3("どう", "あって", "も", PartOfSpeech.Expression),
    // Checked before SpecialCases2's じゃ+ない so the か is absorbed into じゃないか 2819990.
    SpecialCase3("じゃ", "ない", "か", PartOfSpeech.Expression),
)

internal val AuxiliaryVerbs: Set<String> = linkedSetOf(
    "続ける", "始める", "終わる", "終える", "出す", "かける", "いたす", "いただく", "頂く", "する", "回る",
)

internal val TeFormSubsidiaryVerbs: Set<String> = linkedSetOf(
    "あげる", "上げる",
    "くれる", "呉れる",
    "もらう", "貰う",
    "やる",
    // 差し上げる is absent: the honorific benefactive is a content verb kept as its own token (判じて + 差し上げる).
    "くださる", "下さる",
    "おく", "置く",
    "みる", "見る",
)

// Aux for verb + て/で + aux merges; each needs deconjugator rules so the merged token keeps its conjugation info.
internal val TeFormAuxChainVerbs: Set<String> = linkedSetOf(
    "いる", "居る",
    "ある", "有る",
    "おく", "置く",
    "しまう", "仕舞う",
    "いく", "行く",
    "くる", "来る",
    "みる", "見る",
)

internal val AuxiliaryVerbStems: Map<String, String> = linkedMapOf(
    "続ける" to "続け", "始める" to "始め", "終わる" to "終わ",
    "終える" to "終え", "出す" to "出", "かける" to "かけ",
    "回る" to "回", "合う" to "合", "放つ" to "放",
)

internal val CompoundVerbSplitSuffixes: Set<String> = LinkedHashSet(AuxiliaryVerbs) + listOf("合う", "放つ")

internal val SpecialCases2: Set<SpecialCase2> = linkedSetOf(
    SpecialCase2("じゃ", "ない", PartOfSpeech.Expression),
    SpecialCase2("とんでも", "なく", PartOfSpeech.IAdjective), // adverbial とんでもない; Sudachi splits off the adverb とんでも
    SpecialCase2("とんでも", "なかっ", PartOfSpeech.IAdjective),
    SpecialCase2("本当", "に", PartOfSpeech.Adverb), // 本当に 1611580; Sudachi sometimes splits it
    SpecialCase2("ため", "に", PartOfSpeech.Conjunction), // ために 1157150
    SpecialCase2("だ", "ろう", PartOfSpeech.Auxiliary), // Sudachi shreds そりゃそうだろう into …だ|ろう(蝋)
    SpecialCase2("す", "べき", PartOfSpeech.Expression), // classical す + べき → すべき 1006200 (とすべき戦術)
    SpecialCase2("なさ", "すぎる", PartOfSpeech.Verb), // なさ[ない]+すぎる → ない via さすぎる excess deconj
    SpecialCase2("なさ", "すぎ", PartOfSpeech.Verb),
    SpecialCase2("近", "すぎ", PartOfSpeech.Verb), // adjective stem + すぎ → 近い via excess deconj
    SpecialCase2("近", "すぎる", PartOfSpeech.Verb),
    SpecialCase2("だ", "けど", PartOfSpeech.Conjunction),
    SpecialCase2("だ", "が", PartOfSpeech.Conjunction),
    SpecialCase2("で", "さえ", PartOfSpeech.Expression),
    SpecialCase2("で", "すら", PartOfSpeech.Expression),
    SpecialCase2("と", "いう", PartOfSpeech.Expression),
    SpecialCase2("と", "して", PartOfSpeech.Expression),
    SpecialCase2("と", "か", PartOfSpeech.Conjunction),
    SpecialCase2("だ", "から", PartOfSpeech.Conjunction),
    SpecialCase2("から", "して", PartOfSpeech.Expression),
    SpecialCase2("これ", "まで", PartOfSpeech.Expression),
    SpecialCase2("それ", "も", PartOfSpeech.Conjunction),
    SpecialCase2("それ", "だけ", PartOfSpeech.Noun),
    SpecialCase2("くせ", "に", PartOfSpeech.Conjunction),
    SpecialCase2("なの", "に", PartOfSpeech.Conjunction),
    SpecialCase2("なの", "で", PartOfSpeech.Conjunction),
    SpecialCase2("の", "で", PartOfSpeech.Particle),
    SpecialCase2("誰", "も", PartOfSpeech.Expression),
    SpecialCase2("誰", "か", PartOfSpeech.Expression),
    SpecialCase2("すぐ", "に", PartOfSpeech.Adverb),
    SpecialCase2("たしか", "に", PartOfSpeech.Adverb),
    SpecialCase2("確か", "に", PartOfSpeech.Adverb), // 確かに 1205770
    SpecialCase2("直", "に", PartOfSpeech.Adverb), // 直に 1430690 (じかに); the bare 直 otherwise reads ちょく
    SpecialCase2("ゆえ", "に", PartOfSpeech.Conjunction), // 故に 1267130
    SpecialCase2("故", "に", PartOfSpeech.Conjunction),
    SpecialCase2("とう", "に", PartOfSpeech.Adverb), // 疾うに 1633370; the bare とう otherwise strands on 塔
    SpecialCase2("そ", "も", PartOfSpeech.Conjunction), // そも 2836401; Sudachi emits archaic pronoun そ + も
    SpecialCase2("どう", "こう", PartOfSpeech.Adverb), // どうこう 2670710
    SpecialCase2("何で", "も", PartOfSpeech.Adverb), // 何でも 1611030
    SpecialCase2("ただ", "今", PartOfSpeech.Adverb),
    SpecialCase2("ところ", "で", PartOfSpeech.Conjunction),
    SpecialCase2("度", "に", PartOfSpeech.Expression),
    SpecialCase2("なん", "か", PartOfSpeech.Particle),
    SpecialCase2("なん", "て", PartOfSpeech.Particle),
    SpecialCase2("だっ", "た", PartOfSpeech.Expression),
    SpecialCase2("だっ", "たら", PartOfSpeech.Conjunction),
    SpecialCase2("よう", "に", PartOfSpeech.Expression),
    SpecialCase2("ん", "です", PartOfSpeech.Expression),
    SpecialCase2("ん", "だ", PartOfSpeech.Expression),
    SpecialCase2("です", "か", PartOfSpeech.Expression),
    SpecialCase2("し", "て", PartOfSpeech.Verb),
    SpecialCase2("し", "ちゃ", PartOfSpeech.Verb),
    SpecialCase2("何", "の", PartOfSpeech.Pronoun),
    SpecialCase2("カッコ", "いい", PartOfSpeech.IAdjective),
    SpecialCase2("ドキッ", "と", PartOfSpeech.Adverb),
    SpecialCase2("ドキっ", "と", PartOfSpeech.Adverb),
    SpecialCase2("チラッ", "と", PartOfSpeech.Adverb),
    SpecialCase2("チラっ", "と", PartOfSpeech.Adverb),
    SpecialCase2("ニッ", "と", PartOfSpeech.Adverb),
    SpecialCase2("にっ", "と", PartOfSpeech.Adverb),
    SpecialCase2("か", "な", PartOfSpeech.Particle),
    SpecialCase2("わ", "い", PartOfSpeech.Particle),
    SpecialCase2("だ", "い", PartOfSpeech.Particle), // familiar question marker だい (思っただけだい！)
    SpecialCase2("よう", "です", PartOfSpeech.Expression),
    SpecialCase2("何も", "かも", PartOfSpeech.Expression),
    SpecialCase2("何と", "も", PartOfSpeech.Adverb),
    SpecialCase2("何と", "なく", PartOfSpeech.Adverb),
    SpecialCase2("中途", "半端", PartOfSpeech.Noun),
    SpecialCase2("常", "に", PartOfSpeech.Adverb),
    SpecialCase2("なくて", "も", PartOfSpeech.Expression),
    SpecialCase2("なんに", "も", PartOfSpeech.Adverb),
    SpecialCase2("なし", "で", PartOfSpeech.Expression),
    SpecialCase2("なん", "で", PartOfSpeech.Adverb),
    SpecialCase2("に", "とって", PartOfSpeech.Expression),
    SpecialCase2("に", "ついて", PartOfSpeech.Expression),
    SpecialCase2("だ", "って", PartOfSpeech.Conjunction),
    SpecialCase2("どこ", "か", PartOfSpeech.Pronoun),
    SpecialCase2("急", "に", PartOfSpeech.Adverb),
    SpecialCase2("と", "ても", PartOfSpeech.Adverb),
    SpecialCase2("で", "も", PartOfSpeech.Conjunction),
    SpecialCase2("として", "も", PartOfSpeech.Expression),
    SpecialCase2("多", "き", PartOfSpeech.IAdjective),
    SpecialCase2("ぶっ", "た", PartOfSpeech.Suffix),
    SpecialCase2("に", "よる", PartOfSpeech.Expression),
    SpecialCase2("に", "より", PartOfSpeech.Expression),
    SpecialCase2("とっく", "に", PartOfSpeech.Adverb),
    SpecialCase2("おい", "で", PartOfSpeech.Expression),
    SpecialCase2("どうして", "も", PartOfSpeech.Adverb),
    SpecialCase2("か", "も", PartOfSpeech.Particle),
    SpecialCase2("かも", "しれない", PartOfSpeech.Expression),
    SpecialCase2("何に", "も", PartOfSpeech.Adverb),
    SpecialCase2("ま", "だ", PartOfSpeech.Adverb),
    SpecialCase2("いっしょ", "に", PartOfSpeech.Adverb),
    SpecialCase2("と", "言った", PartOfSpeech.Conjunction),
    SpecialCase2("その", "時", PartOfSpeech.Expression),
    SpecialCase2("そう", "いえば", PartOfSpeech.Expression),
    SpecialCase2("出来", "なさそう", PartOfSpeech.Verb),
    SpecialCase2("それ", "相応", PartOfSpeech.NaAdjective),
    SpecialCase2("当", "たった", PartOfSpeech.Verb),
    SpecialCase2("こそ", "あれ", PartOfSpeech.Conjunction),
    SpecialCase2("こう", "やって", PartOfSpeech.Conjunction),
    SpecialCase2("そう", "やって", PartOfSpeech.Conjunction),
    SpecialCase2("って", "ば", PartOfSpeech.Particle),
    SpecialCase2("かく", "も", PartOfSpeech.NominalAdjective),
    SpecialCase2("よう", "さん", PartOfSpeech.NominalAdjective),
    SpecialCase2("と", "する", PartOfSpeech.Expression),
    SpecialCase2("しきり", "と", PartOfSpeech.Adverb),
    SpecialCase2("しきり", "に", PartOfSpeech.Adverb),
    SpecialCase2("ない", "ない", PartOfSpeech.IAdjective),
    SpecialCase2("ビクと", "も", PartOfSpeech.Adverb),
    SpecialCase2("びくと", "も", PartOfSpeech.Adverb),
    SpecialCase2("要する", "に", PartOfSpeech.Expression),
    SpecialCase2("この", "前", PartOfSpeech.Adverb),
    SpecialCase2("ならび", "に", PartOfSpeech.Conjunction),
    SpecialCase2("残虐", "非道", PartOfSpeech.NaAdjective),
    SpecialCase2("一緒", "に", PartOfSpeech.Adverb),
    SpecialCase2("別", "に", PartOfSpeech.Adverb),
    SpecialCase2("先", "に", PartOfSpeech.Adverb),
    SpecialCase2("仮", "に", PartOfSpeech.Adverb),
    SpecialCase2("滅多", "に", PartOfSpeech.Adverb),
    SpecialCase2("何", "で", PartOfSpeech.Adverb),
    SpecialCase2("何", "か", PartOfSpeech.Pronoun),
    SpecialCase2("何", "も", PartOfSpeech.Pronoun),
    SpecialCase2("実", "は", PartOfSpeech.Expression),
    SpecialCase2("後", "で", PartOfSpeech.Expression),
    SpecialCase2("元", "は", PartOfSpeech.Expression),
    SpecialCase2("当", "の", PartOfSpeech.NominalAdjective),
    SpecialCase2("真", "の", PartOfSpeech.NominalAdjective),
    SpecialCase2("大", "の", PartOfSpeech.NominalAdjective),
    SpecialCase2("例", "の", PartOfSpeech.Adverb),
    SpecialCase2("か", "い", PartOfSpeech.Particle),
    SpecialCase2("露", "にして", PartOfSpeech.Expression),
    SpecialCase2("なれ", "ど", PartOfSpeech.Conjunction),
    SpecialCase2("ここ", "ぞ", PartOfSpeech.Expression),
    SpecialCase2("並び", "に", PartOfSpeech.Conjunction),
    SpecialCase2("真っ先", "に", PartOfSpeech.Adverb),
    SpecialCase2("です", "から", PartOfSpeech.Conjunction),
    SpecialCase2("つ", "か", PartOfSpeech.Conjunction),
    SpecialCase2("それ", "じゃ", PartOfSpeech.Conjunction),
    SpecialCase2("を", "以って", PartOfSpeech.Expression),
    SpecialCase2("に", "つれ", PartOfSpeech.Conjunction),
    SpecialCase2("いつまで", "も", PartOfSpeech.Adverb),
    SpecialCase2("だから", "こそ", PartOfSpeech.Expression),
    SpecialCase2("では", "あるまい", PartOfSpeech.Expression),
    SpecialCase2("に", "於いて", PartOfSpeech.Expression),
    // user_dic にせよ only wins the lattice after なる; after other verbs Sudachi cuts に+せよ(為る命令形) (成就するにせよ).
    SpecialCase2("に", "せよ", PartOfSpeech.Expression),
    // Sudachi splits colloquial どっか/どっから as どっ(代名詞)+particle
    SpecialCase2("どっ", "か", PartOfSpeech.Adverb),
    SpecialCase2("どっ", "から", PartOfSpeech.Adverb),
    // Matches in CombineFinal after CombineTte builds ちゃってぇ; the lookup's small-kana strip resolves なんちゃって.
    SpecialCase2("なん", "ちゃってぇ", PartOfSpeech.Expression),
    SpecialCase2("ばかり", "に", PartOfSpeech.Expression), // ばかりに 1010250
    SpecialCase2("それ", "では", PartOfSpeech.Conjunction), // それでは 1406050
    SpecialCase2("に", "対して", PartOfSpeech.Expression), // に対して 1009800
    SpecialCase2("いい", "ぞ", PartOfSpeech.Interjection),
    SpecialCase2("に", "よって", PartOfSpeech.Expression),
)

internal val SentenceEnders: Set<Char> = hashSetOf('。', '！', '？', '」')

internal const val StopToken = "|"
internal const val BatchDelimiter = "|||"

// Private-use char tagging a line-final ）; preprocessing erases line structure, so the tag carries it to the sentence splitter.
internal const val LineEndParenMark = ''

internal val NCompoundSuffixes: Set<String> =
    linkedSetOf("だ", "です", "じゃ", "なら", "ても", "でも", "だろ", "だろう", "だって", "だけど", "だけ", "だが", "だし", "だから")

internal val DaCompoundSuffixes: Set<String> =
    linkedSetOf("が", "けど", "けれど", "けれども", "から", "し", "って", "の")

internal val VerbIndicatingAuxiliaries: Set<String> =
    linkedSetOf("られる", "れる", "せる", "させる")

internal val GodanVerbEndings: Array<String> = arrayOf("る", "す", "つ", "く", "ぐ", "む", "ぶ", "ぬ", "う")

internal val GodanVolitionalOKana: Set<Char> =
    hashSetOf('お', 'こ', 'ご', 'そ', 'ぞ', 'と', 'ど', 'の', 'ほ', 'ぼ', 'ぽ', 'も', 'よ', 'ろ')
