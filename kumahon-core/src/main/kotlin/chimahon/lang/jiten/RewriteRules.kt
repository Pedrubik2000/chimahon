// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.RewriteRules.cs
package chimahon.lang.jiten

// A rule matches 1-3 tokens (plus prev/next/window context and a lookup guard) and rewrites them from templates.
// The engine owns offsets, readings (a re-cut never keeps a stale one), cloning and conjugation recovery.

internal enum class RewritePhase {
    // Runs where ProcessSpecialCases sits (before the combine stages).
    Early,
    // Runs after RepairQuotativeTte/RecombineHiraganaTokens, where the mora-theft repairs live.
    Late,
    // Runs immediately before ApplyContextPins (surface-keyed disambiguation pins near the pipeline end).
    Cleanup,
    // Runs immediately before FixReadingAmbiguity (reading/pin-only 1:1 remaps).
    Reading,
}

internal enum class LookupGuardKind {
    CompoundExists,        // HasCompoundLookup(expanded)
    NonNameCompoundExists, // HasNonNameCompoundLookup(expanded)
    CompoundAbsent,        // !HasCompoundLookup(expanded); theft repairs gate on the whole surface NOT being a word
    FrequencyRankUnder,    // GetNonNameCompoundFrequencyRank(expanded) < Rank
}

// Text/TextAnyOf are indexed, StartsWith/EndsWith scanned per token; null = any; RequireUnpinned respects earlier pins.
internal class TokenPattern(
    val text: String? = null,
    val textAnyOf: Array<String>? = null,
    val textStartsWith: String? = null,
    val textEndsWith: String? = null,
    val pos: Array<PartOfSpeech>? = null,
    val dictFormAnyOf: Array<String>? = null,
    val normalizedFormAnyOf: Array<String>? = null,
    val readingPrefix: String? = null,
    val notReadingPrefix: String? = null,
    val requireUnpinned: Boolean = true,
)

// Text "" keeps the matched surface; Reading is required for splits/merges (null keeps it on 1:1). Pin sets PreMatchedWordId.
internal class TokenTemplate(
    val text: String,
    val dictForm: String? = null,
    val normalizedForm: String? = null,
    val pos: PartOfSpeech? = null,
    val posSection: PartOfSpeechSection? = null,
    val reading: String? = null,
    val pin: Int? = null,
    val pinReadingIndex: Int? = null, // C# byte?
    // Final word decision: lookup-time compound matching must not swallow the token into a longer span.
    val hardPin: Boolean = false,
    val recoverConjugations: Boolean = false,
)

// Constraints AND, Negate flips; ClauseBoundary = a Symbol/SupplementarySymbol/BlankSpace neighbour or the list edge.
// Numeral also matches Sudachi's 名詞-数詞 nouns (百, ５０), which PosAnyOf: [Numeral] misses.
internal class ContextCond(
    val textAnyOf: Array<String>? = null,
    val textEndsWithAnyOf: Array<String>? = null,
    val textStartsWithAnyOf: Array<String>? = null,
    val posAnyOf: Array<PartOfSpeech>? = null,
    val clauseBoundary: Boolean = false,
    val numeral: Boolean = false,
    val negate: Boolean = false,
)

// Some token in [From, To] relative to the match start satisfies the constraints (帽子 near ツバ = brim); Negate flips.
internal class WindowCond(
    val from: Int,
    val to: Int,
    val textAnyOf: Array<String>? = null,
    val posAnyOf: Array<PartOfSpeech>? = null,
    val negate: Boolean = false,
)

// Pattern expands over matched surfaces ("{0}{1}", "{0}い", literal "貸りる"); no lambdas, so rules stay pure data.
internal class LookupGuard(val kind: LookupGuardKind, val pattern: String, val rank: Int? = null)

internal class RewriteRule(
    val id: String,
    val phase: RewritePhase,
    val match: Array<TokenPattern>,
    val replace: Array<TokenTemplate>,
    val prev: ContextCond? = null,
    val next: ContextCond? = null,
    val window: WindowCond? = null,
    val guard: LookupGuard? = null,
)

// Kana-surface pins stay in ApplyContextPins: a rule pin sets PinnedByRewriteRule, exempting the token from misparse gates.
// (C#'s one RewriteRulesTable array, split into chunks so no JVM method exceeds 64 KB.)
private val rewriteRulesTable: Array<RewriteRule> by lazy {
    arrayOf(*rewriteRules1(), *rewriteRules2(), *rewriteRules3(), *rewriteRules4())
}

private fun rewriteRules1(): Array<RewriteRule> = arrayOf(
        // Colloquial なんなん ("what the hell?"), unless followed by と (喃々と taru-adverb).
        RewriteRule("nannan", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "なんなん", requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "なんなん", pos = PartOfSpeech.Expression, pin = 2871194)),
            next = ContextCond(textAnyOf = arrayOf("と"), negate = true)),

        // クズ = 屑 "scum", not 葛 "arrowroot".
        RewriteRule("kuzu", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "クズ", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), requireUnpinned = false)),
            arrayOf(TokenTemplate("", pin = 1246510))),

        // あるある = the "I can relate" expression, not doubled ある.
        RewriteRule("aruaru", RewritePhase.Cleanup,
            arrayOf(TokenPattern(textAnyOf = arrayOf("あるある", "アルアル"), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "あるある", normalizedForm = "あるある", pos = PartOfSpeech.Interjection, pin = 2150380))),

        // 事 read ゴト after a noun/verb stem is the suffix ごと, not the noun こと.
        RewriteRule("goto-suffix", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "事", readingPrefix = "ゴト", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pin = 2613010)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.Verb))),

        // ナシ = the negation なし, not the pear 梨.
        RewriteRule("nashi", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "ナシ",
                pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.NounSuffix, PartOfSpeech.Suffix),
                requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "なし", normalizedForm = "なし", pin = 1529560))),

        // Standalone じゃが is the archaic conjunction (じゃが、…; 理由じゃが), not the potato; じゃがバター stays potato.
        RewriteRule("jaga-conjunction", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "じゃが", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), requireUnpinned = false)),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Conjunction, pin = 2856812)),
            next = ContextCond(textAnyOf = arrayOf("バター", "いも", "イモ", "芋"), negate = true)),

        // Pronoun おら/オラ is 俺; unpinned, katakana オラ's exact-surface match hands it to hola.
        RewriteRule("ora-pronoun", RewritePhase.Cleanup,
            arrayOf(TokenPattern(textAnyOf = arrayOf("おら", "オラ"), pos = arrayOf(PartOfSpeech.Pronoun), requireUnpinned = false)),
            arrayOf(TokenTemplate("", pin = 2080360, pinReadingIndex = 3))),

        // す before explanatory ん is contracted する (すんだ), not 酢/素/巣; hard, or す+んだ re-fuses into 済んだ.
        RewriteRule("su-contracted-suru", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "す", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("する", "為る"))),
            arrayOf(TokenTemplate("", pin = 1157170, pinReadingIndex = 1, hardPin = true)),
            next = ContextCond(textAnyOf = arrayOf("ん", "んだ", "んで"))),

        // Sudachi's fused すん (すんの, すんな) is する; unpinned it can only resolve to 済む/住む lookalikes.
        RewriteRule("sun-contracted-suru", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "すん", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("する", "為る"))),
            arrayOf(TokenTemplate("", pin = 1157170, pinReadingIndex = 1, hardPin = true))),

        // あ before explanatory ん is contracted ある (あんだ); hard, or あ+んだ re-fuses into 安打.
        RewriteRule("a-contracted-aru", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "あ", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("ある", "有る", "在る"))),
            arrayOf(TokenTemplate("", pin = 1296400, pinReadingIndex = 2, hardPin = true)),
            next = ContextCond(textAnyOf = arrayOf("ん", "んだ", "んで"))),

        // The same contraction fused by Sudachi as あん (あんの, あんだろ), lemmatised as ある.
        RewriteRule("an-contracted-aru", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "あん", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("ある", "有る", "在る"))),
            arrayOf(TokenTemplate("", pin = 1296400, pinReadingIndex = 2, hardPin = true))),

        // カンパン = the food 乾パン, not 肝斑/甲板/乾板.
        RewriteRule("kanpan", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "カンパン", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "乾パン", normalizedForm = "乾パン", pin = 1209690))),

        // Kana した after genitive の is 下, not 舌.
        RewriteRule("shita-shita", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "した", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun))),
            arrayOf(TokenTemplate("", dictForm = "下", normalizedForm = "下", pin = 1184140)),
            prev = ContextCond(textAnyOf = arrayOf("の"), posAnyOf = arrayOf(PartOfSpeech.Particle))),

        // ならば = the conditional conjunction, not a form of なる.
        RewriteRule("naraba", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "ならば", dictFormAnyOf = arrayOf("なる", "成る", "ならば", "だ"), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "ならば", normalizedForm = "ならば", pos = PartOfSpeech.Conjunction, pin = 1009470))),

        // Elongated だろー = だろう.
        RewriteRule("darou", RewritePhase.Cleanup,
            arrayOf(TokenPattern(textAnyOf = arrayOf("だろー", "だろぉ", "だろぉー"), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "だろう", normalizedForm = "だろう", pin = 1928670))),

        // つー before a nominaliser/question is the という contraction.
        RewriteRule("tsuu", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "つー", requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "という", normalizedForm = "という", pos = PartOfSpeech.Particle, pin = 1922760)),
            next = ContextCond(textAnyOf = arrayOf("の", "か", "こと", "わけ"))),

        // 向い* lemmatised as 向く is 向く, not 向かう.
        RewriteRule("mukai", RewritePhase.Cleanup,
            arrayOf(TokenPattern(textStartsWith = "向い", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("向く"))),
            arrayOf(TokenTemplate("", dictForm = "向く", normalizedForm = "向く", pin = 1277080, recoverConjugations = true))),

        // 共 (noun とも) + に is the adverb 共に "together"; Sudachi leaves the two split.
        RewriteRule("tomoni", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "共", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), readingPrefix = "トモ", requireUnpinned = false),
             TokenPattern(text = "に", pos = arrayOf(PartOfSpeech.Particle), requireUnpinned = false)),
            arrayOf(TokenTemplate("共に", dictForm = "共に", normalizedForm = "共に", pos = PartOfSpeech.Adverb, reading = "トモニ", pin = 1234260))),

        // 来 + adnominal たる before a noun is 来たる (きたる, "coming/next"), not 来る with a たる aux.
        RewriteRule("kitaru", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "来", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("来る"), requireUnpinned = false),
             TokenPattern(text = "たる", pos = arrayOf(PartOfSpeech.Auxiliary), requireUnpinned = false)),
            arrayOf(TokenTemplate("来たる", dictForm = "来たる", normalizedForm = "来たる", pos = PartOfSpeech.PrenounAdjectival, reading = "キタル", pin = 1591270)),
            next = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun))),

        // そうした/こうした/ああした before a noun is the adnominal "such", not した+もの → 下物; verbal そうしたら stays out.
        RewriteRule("soushita", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "そう", pos = arrayOf(PartOfSpeech.Adverb), requireUnpinned = false),
             TokenPattern(text = "した", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("する", "為る"), requireUnpinned = false)),
            arrayOf(TokenTemplate("そうした", dictForm = "そうした", normalizedForm = "そうした", pos = PartOfSpeech.PrenounAdjectival, reading = "ソウシタ", pin = 2008650)),
            next = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun))),
        RewriteRule("koushita", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "こう", pos = arrayOf(PartOfSpeech.Adverb), requireUnpinned = false),
             TokenPattern(text = "した", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("する", "為る"), requireUnpinned = false)),
            arrayOf(TokenTemplate("こうした", dictForm = "こうした", normalizedForm = "こうした", pos = PartOfSpeech.PrenounAdjectival, reading = "コウシタ", pin = 2008030)),
            next = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun))),
        RewriteRule("aashita", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "ああ", pos = arrayOf(PartOfSpeech.Adverb), requireUnpinned = false),
             TokenPattern(text = "した", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("する", "為る"), requireUnpinned = false)),
            arrayOf(TokenTemplate("ああした", dictForm = "ああした", normalizedForm = "ああした", pos = PartOfSpeech.PrenounAdjectival, reading = "アアシタ", pin = 2085100)),
            next = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun))),

        // Sudachi shreds ほくそ笑む; runs Late, after mora-theft reforms 笑む and before the short-kana filter drops ほく/そ.
        RewriteRule("hokusoemu", RewritePhase.Late,
            arrayOf(TokenPattern(text = "ほく", pos = arrayOf(PartOfSpeech.Adverb), requireUnpinned = false),
             TokenPattern(text = "そ", requireUnpinned = false),
             TokenPattern(text = "笑む", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("笑む"), requireUnpinned = false)),
            arrayOf(TokenTemplate("ほくそ笑む", dictForm = "ほくそ笑む", normalizedForm = "ほくそ笑む", pos = PartOfSpeech.Verb, reading = "ホクソエム", pin = 2065260, recoverConjugations = true))),

        // 合 after 死 is the 合い suffix あい (死合 = しあい, a duel), not the volume unit ごう.
        RewriteRule("shiai-ai", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "合", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "合い", normalizedForm = "合い", reading = "アイ", pin = 1284320)),
            prev = ContextCond(textAnyOf = arrayOf("死"))),

        // Bare 有り得 at a clause end is the entry 有り得, not the verb 有り得る it deconjugates to.
        RewriteRule("ariu", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "有り得", pos = arrayOf(PartOfSpeech.Verb), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "有り得", normalizedForm = "有り得", pin = 2560320)),
            next = ContextCond(clauseBoundary = true)),

        // 飛ばし after 首 is 飛ばす "to send flying", not the securities-fraud noun 飛ばし (1637130).
        RewriteRule("kubi-tobashi", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "飛ばし", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), dictFormAnyOf = arrayOf("飛ばし"), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "飛ばす", normalizedForm = "飛ばす", pos = PartOfSpeech.Verb, reading = "トバシ", pin = 1485230, recoverConjugations = true)),
            prev = ContextCond(textAnyOf = arrayOf("首"))),

        // 羽馬(surname)+車 is a winged carriage (羽 + 馬車); exact two-token match so other names aren't re-cut.
        RewriteRule("hane-basha", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "羽馬", requireUnpinned = false),
             TokenPattern(text = "車", requireUnpinned = false)),
            arrayOf(TokenTemplate("羽", dictForm = "羽", normalizedForm = "羽", pos = PartOfSpeech.Noun, reading = "ハネ", pin = 1171680),
             TokenTemplate("馬車", dictForm = "馬車", normalizedForm = "馬車", pos = PartOfSpeech.Noun, reading = "バシャ", pin = 1471780))),

        // Sudachi cuts 行かせまい as 行|か|せまい(狭い); no Prev needed since a real 行か、狭い keeps kanji or punctuation.
        RewriteRule("ikasemai", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "行", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun)),
             TokenPattern(text = "か", pos = arrayOf(PartOfSpeech.Particle)),
             TokenPattern(text = "せまい")),
            arrayOf(TokenTemplate("行かせまい", dictForm = "行く", normalizedForm = "行く", pos = PartOfSpeech.Verb,
                reading = "イカセマイ", pin = 1578850, pinReadingIndex = 0, recoverConjugations = true))),

        // っこない after a verb stem (行け|っこ|ない); the verb gate spares nominal っこ (慣れっこ, かけっこ).
        RewriteRule("kkonai", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "っこ"),
             TokenPattern(text = "ない")),
            arrayOf(TokenTemplate("っこない", dictForm = "っこない", normalizedForm = "っこない", pos = PartOfSpeech.Expression,
                reading = "ッコナイ", pin = 2145640, pinReadingIndex = 0)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb))),

        // Explanatory なんです, matched Early before tail fusion; non-question tails rule out 何, Pronoun hosts stay out.
        RewriteRule("nandesu", RewritePhase.Early,
            arrayOf(TokenPattern(text = "な"),
             TokenPattern(text = "ん"),
             TokenPattern(text = "です")),
            arrayOf(TokenTemplate("なんです", dictForm = "なんです", normalizedForm = "なんです", pos = PartOfSpeech.Expression,
                reading = "ナンデス", pin = 2683060, pinReadingIndex = 0)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.Suffix,
                PartOfSpeech.Name, PartOfSpeech.NaAdjective)),
            next = ContextCond(textAnyOf = arrayOf("か", "？"), negate = true)),

        // A question tail is ambiguous (趣味なんですか); a na-adjective host rules out 何 (好き何ですか), a bare noun doesn't.
        RewriteRule("nandesu-ka-naadj", RewritePhase.Early,
            arrayOf(TokenPattern(text = "な"),
             TokenPattern(text = "ん"),
             TokenPattern(text = "です")),
            arrayOf(TokenTemplate("なんです", dictForm = "なんです", normalizedForm = "なんです", pos = PartOfSpeech.Expression,
                reading = "ナンデス", pin = 2683060, pinReadingIndex = 0)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.NaAdjective)),
            next = ContextCond(textAnyOf = arrayOf("か", "？"))),

        // A genitive の before the host also rules out 何: 俺のせいなんですか ("it's MY fault?!").
        RewriteRule("nandesu-ka-no", RewritePhase.Early,
            arrayOf(TokenPattern(text = "な"),
             TokenPattern(text = "ん"),
             TokenPattern(text = "です")),
            arrayOf(TokenTemplate("なんです", dictForm = "なんです", normalizedForm = "なんです", pos = PartOfSpeech.Expression,
                reading = "ナンデス", pin = 2683060, pinReadingIndex = 0)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.Suffix,
                PartOfSpeech.Name)),
            next = ContextCond(textAnyOf = arrayOf("か", "？")),
            window = WindowCond(-2, -2, textAnyOf = arrayOf("の"))),

        // Settles 見るも無残 before the na-adjective merge absorbs the connector な.
        RewriteRule("mirumo-muzan", RewritePhase.Late,
            arrayOf(TokenPattern(text = "見るも"),
             TokenPattern(text = "無残")),
            arrayOf(TokenTemplate("見るも無残", dictForm = "見るも無残", normalizedForm = "見るも無残", pos = PartOfSpeech.Expression,
                reading = "ミルモムザン", pin = 2871068, pinReadingIndex = 0))),
        // Shape where the na-adjective merge already absorbed the connector (無残な).
        RewriteRule("mirumo-muzan-na", RewritePhase.Late,
            arrayOf(TokenPattern(text = "見るも"),
             TokenPattern(text = "無残な")),
            arrayOf(TokenTemplate("見るも無残", dictForm = "見るも無残", normalizedForm = "見るも無残", pos = PartOfSpeech.Expression,
                reading = "ミルモムザン", pin = 2871068, pinReadingIndex = 0),
             TokenTemplate("な", dictForm = "な", normalizedForm = "な", pos = PartOfSpeech.Auxiliary, reading = "ナ"))),

        // JMnedict's place entry 養護院 outranks noun+院; per-surface since a blanket recut would shred genuine names.
        RewriteRule("yougoin", RewritePhase.Late,
            arrayOf(TokenPattern(text = "養護院", requireUnpinned = false)),
            arrayOf(TokenTemplate("養護", dictForm = "養護", normalizedForm = "養護", pos = PartOfSpeech.Noun,
                reading = "ヨウゴ", pin = 1605847, pinReadingIndex = 0, hardPin = true),
             TokenTemplate("院", dictForm = "院", normalizedForm = "院", pos = PartOfSpeech.Noun,
                reading = "イン", pin = 2414530, pinReadingIndex = 0, hardPin = true))),

        // 打ち下ろし is deverbal 打ち下ろす; JMDict's only noun entry is the golf term (downhill hole).
        RewriteRule("uchioroshi", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "打ち下ろし", requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "打ち下ろす", normalizedForm = "打ち下ろす", pos = PartOfSpeech.Verb,
                reading = "ウチオロシ", pin = 1408600, pinReadingIndex = 0, recoverConjugations = true))),

        // Sentence-final だい after explanatory ん: the fused んだ strands the い, which then drops.
        RewriteRule("ndai", RewritePhase.Late,
            arrayOf(TokenPattern(text = "んだ"),
             TokenPattern(text = "い", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(TokenTemplate("ん", dictForm = "ん", normalizedForm = "ん", pos = PartOfSpeech.Particle, reading = "ン", pin = 2139720),
             TokenTemplate("だい", dictForm = "だい", normalizedForm = "だい", pos = PartOfSpeech.Particle,
                reading = "ダイ", pin = 2097680, pinReadingIndex = 0))),

        // Sudachi prefers 上様, so 母|上様 never yields 母上; closed host list since a generic re-cut hits 滑走|路上.
        RewriteRule("kinship-uesama", RewritePhase.Early,
            arrayOf(TokenPattern(textAnyOf = arrayOf("父", "母", "兄", "姉", "祖父", "祖母", "義父", "義母"),
                pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun)),
             TokenPattern(text = "上様")),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Noun),
             TokenTemplate("上", dictForm = "上", normalizedForm = "上", pos = PartOfSpeech.Suffix, reading = "ウエ"),
             TokenTemplate("様", dictForm = "様", normalizedForm = "様", pos = PartOfSpeech.Suffix,
                reading = "サマ", pin = 1545790, pinReadingIndex = 0))),

        // Rustic くだせえ (slurred 下さい) shreds to くだ+せえ; the deconjugator's slur fold recovers ください.
        RewriteRule("kudasee", RewritePhase.Late,
            arrayOf(TokenPattern(text = "くだ"),
             TokenPattern(text = "せえ")),
            arrayOf(TokenTemplate("くだせえ", dictForm = "ください", normalizedForm = "ください", pos = PartOfSpeech.Expression,
                reading = "クダセエ", pin = 1184270, pinReadingIndex = 1, recoverConjugations = true))),

        // Dialect ちょる (=ておる) after a verb; the verb gate spares the interjection ちょっ (ちょっ、待て).
        RewriteRule("chotta", RewritePhase.Late,
            arrayOf(TokenPattern(text = "ちょっ"),
             TokenPattern(text = "た", pos = arrayOf(PartOfSpeech.Auxiliary))),
            arrayOf(TokenTemplate("ちょった", dictForm = "ちょる", normalizedForm = "ちょる", pos = PartOfSpeech.Auxiliary,
                reading = "チョッタ", pin = 2869627, pinReadingIndex = 0, recoverConjugations = true)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb, PartOfSpeech.Auxiliary))),

        // Kansai のうなる ("to disappear"), which Sudachi reads as の + 唸る.
        RewriteRule("nounatta", RewritePhase.Late,
            arrayOf(TokenPattern(text = "の", pos = arrayOf(PartOfSpeech.Particle)),
             TokenPattern(textStartsWith = "うなっ", pos = arrayOf(PartOfSpeech.Verb)),
             TokenPattern(text = "た", pos = arrayOf(PartOfSpeech.Auxiliary))),
            arrayOf(TokenTemplate("のうなった", dictForm = "のうなる", normalizedForm = "のうなる", pos = PartOfSpeech.Verb,
                reading = "ノウナッタ", pin = 2793080, pinReadingIndex = 1, recoverConjugations = true)),
            // の after a nominal/predicate host is genitive (彼のうなった声); elsewhere it opens のうなる (もうのうなった).
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb, PartOfSpeech.Auxiliary, PartOfSpeech.IAdjective,
                PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.Name, PartOfSpeech.Pronoun,
                PartOfSpeech.NaAdjective, PartOfSpeech.Prefix, PartOfSpeech.Suffix, PartOfSpeech.Numeral,
                PartOfSpeech.Counter), negate = true)),
)

private fun rewriteRules2(): Array<RewriteRule> = arrayOf(

        // Dialect じゃった (=だった) after nominal content; after a verb it stays the じゃう contraction (飲んじゃった).
        RewriteRule("jatta", RewritePhase.Late,
            arrayOf(TokenPattern(text = "じゃっ", pos = arrayOf(PartOfSpeech.Auxiliary)),
             TokenPattern(text = "た", pos = arrayOf(PartOfSpeech.Auxiliary))),
            arrayOf(TokenTemplate("じゃった", dictForm = "じゃった", normalizedForm = "じゃった", pos = PartOfSpeech.Expression,
                reading = "ジャッタ", pin = 2850797, pinReadingIndex = 0)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.Name,
                PartOfSpeech.Pronoun, PartOfSpeech.NaAdjective, PartOfSpeech.Suffix))),

        // Sudachi cuts 立とうと as 立|とうと (a rare adverb); recut to volitional 立とう + と.
        RewriteRule("tatouto", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "立", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.Name)),
             TokenPattern(text = "とうと")),
            arrayOf(TokenTemplate("立とう", dictForm = "立つ", normalizedForm = "立つ", pos = PartOfSpeech.Verb,
                reading = "タトウ", pin = 1597040, pinReadingIndex = 0, recoverConjugations = true),
             TokenTemplate("と", dictForm = "と", normalizedForm = "と", pos = PartOfSpeech.Particle, reading = "ト"))),

        // ケダ(surname)+モノ作り is ケダモノ (獣) + 作り; bare ケダモノ already resolves.
        RewriteRule("kedamono", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "ケダ", requireUnpinned = false),
             TokenPattern(text = "モノ作り", requireUnpinned = false)),
            arrayOf(TokenTemplate("ケダモノ", dictForm = "獣", normalizedForm = "獣", pos = PartOfSpeech.Noun, reading = "ケダモノ", pin = 1335590),
             TokenTemplate("作り", dictForm = "作り", normalizedForm = "作り", pos = PartOfSpeech.Noun, reading = "ツクリ", pin = 1297250))),

        // 虚(そら)+けど+も after a demonstrative is 虚け (うつけ "fool") + the plural suffix ども.
        RewriteRule("utsuke-domo", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "虚", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), requireUnpinned = false),
             TokenPattern(text = "けど", pos = arrayOf(PartOfSpeech.Particle), requireUnpinned = false),
             TokenPattern(text = "も", pos = arrayOf(PartOfSpeech.Particle), requireUnpinned = false)),
            arrayOf(TokenTemplate("虚け", dictForm = "虚け", normalizedForm = "虚け", pos = PartOfSpeech.Noun, reading = "ウツケ", pin = 2674470),
             TokenTemplate("ども", dictForm = "ども", normalizedForm = "共", pos = PartOfSpeech.Suffix, reading = "ドモ")),
            prev = ContextCond(textAnyOf = arrayOf("この", "その", "あの", "こんな", "そんな", "あんな"))),

        // こった after an adjective is the ことだ contraction (いいこった), not 凝る.
        RewriteRule("kotta-kotoda", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "こった", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "こった", normalizedForm = "こった", pos = PartOfSpeech.Expression, reading = "コッタ", pin = 2106260, pinReadingIndex = 0)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.IAdjective))),

        // Compound matching probes unattested ざまあみやがる, so shredded ざま|あみ|やがれ never reaches its entry.
        RewriteRule("zamaa-miyagare", RewritePhase.Late,
            arrayOf(TokenPattern(text = "ざま", requireUnpinned = false),
             TokenPattern(text = "あみ", requireUnpinned = false),
             TokenPattern(text = "やがれ", requireUnpinned = false)),
            arrayOf(TokenTemplate("ざまあみやがれ", dictForm = "ざまあみやがれ", normalizedForm = "ざまあみやがれ",
                pos = PartOfSpeech.Expression, reading = "ザマーミヤガレ", pin = 2868161, pinReadingIndex = 1))),

        // Katakana イイ is the adjective いい, never the イラン・イラク abbreviation that wins on exact surface.
        RewriteRule("ii-katakana", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "イイ")),
            arrayOf(TokenTemplate("", dictForm = "いい", normalizedForm = "いい", pos = PartOfSpeech.IAdjective,
                reading = "イイ", pin = 2820690, pinReadingIndex = 0))),

        // 被っ* near an abstract-damage noun is こうむる (損失を被った); the clothing かぶる keeps everything else.
        RewriteRule("koumutta", RewritePhase.Cleanup,
            arrayOf(TokenPattern(textStartsWith = "被っ", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("被る"))),
            arrayOf(TokenTemplate("", dictForm = "被る", normalizedForm = "被る", pin = 1484340, recoverConjugations = true)),
            window = WindowCond(-4, -1, textAnyOf = arrayOf("損失", "被害", "損害", "迷惑", "ダメージ", "罰", "不利益"))),

        // あんた = the colloquial pronoun "you", not the past of 編む.
        RewriteRule("anta", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "あんた", requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "あんた", normalizedForm = "あんた", pos = PartOfSpeech.Pronoun, pin = 1979920))),

        // うっす = the colloquial greeting, not 臼/薄.
        RewriteRule("ussu", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "うっす", requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "うっす", normalizedForm = "うっす", pos = PartOfSpeech.Interjection, pin = 2262630))),

        // だっけ = the recollection ending, not だけ.
        RewriteRule("dakke", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "だっけ", requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "だっけ", pos = PartOfSpeech.Expression, pin = 2131200))),

        // いかんせん (如何せん) must not resegment into いかん + せん.
        RewriteRule("ikansen", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "いかんせん", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pin = 1919420))),

        // Prefix-tagged せん is the Kansai negative of する, not the numeral 千.
        RewriteRule("sen-neg", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "せん", pos = arrayOf(PartOfSpeech.Prefix), requireUnpinned = false)),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Expression, pin = 2844926))),

        // セン not after a numeral is 線 "line", not 千.
        RewriteRule("sen-line", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "セン", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pin = 1391780)),
            prev = ContextCond(numeral = true, negate = true)),

        // ヤード after a numeral is the unit (碼), not the working-area ヤード.
        RewriteRule("yard-unit", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "ヤード", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pin = 1136260, pinReadingIndex = 1)),
            prev = ContextCond(numeral = true)),

        // ノリ = 乗り, not 海苔.
        RewriteRule("nori", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "ノリ", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pin = 1354720))),

        // 頚木 = kanji variant of 頸木 (くびき/yoke), absent from lookups.
        RewriteRule("kubiki", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "頚木", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pin = 1831840))),

        // Drawn-out かあ is the question particle か, not the noun カア.
        RewriteRule("kaa", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "かあ", pos = arrayOf(PartOfSpeech.Particle), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "か", normalizedForm = "か", pin = 2028970))),

        // アリアリ = ありあり "vividly", not the currency ariary.
        RewriteRule("ariari", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "アリアリ", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pin = 2007200))),

        // そうそう = "that's right", unless before たる/たり (錚々たる).
        RewriteRule("sousou", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "そうそう", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pin = 1006640)),
            next = ContextCond(textAnyOf = arrayOf("たる", "たり"), negate = true)),

        // いとおしい = 愛おしい, not the archaic verb 射通す.
        RewriteRule("itooshii", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "いとおしい", dictFormAnyOf = arrayOf("いとおす", "射通す"), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "いとおしい", normalizedForm = "愛おしい", pos = PartOfSpeech.IAdjective, pin = 2007340))),

        // してみれば/してみりゃ after から is the connective "from X's standpoint"; 勉強をしてみれば keeps the verb.
        RewriteRule("kara-shitemireba", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "してみれば")),
            arrayOf(TokenTemplate("", dictForm = "してみれば", pos = PartOfSpeech.Expression, pin = 2407670, pinReadingIndex = 1)),
            prev = ContextCond(textAnyOf = arrayOf("から"))),
        RewriteRule("kara-shitemirya", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "してみりゃ")),
            arrayOf(TokenTemplate("", dictForm = "してみれば", pos = PartOfSpeech.Expression, pin = 2407670, pinReadingIndex = 1)),
            prev = ContextCond(textAnyOf = arrayOf("から"))),

        // なかれ = the classical negative imperative 勿れ, not 無い/なし.
        RewriteRule("nakare", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "なかれ", dictFormAnyOf = arrayOf("ない", "なし", "無い"), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "なかれ", normalizedForm = "なかれ", pos = PartOfSpeech.Suffix, pin = 1535750))),

        // Clause-initial つって/つった is the という contraction (釣る needs an object); 魚をつって keeps the verb.
        RewriteRule("tsutte-quotative", RewritePhase.Cleanup,
            arrayOf(TokenPattern(textAnyOf = arrayOf("つって", "つった"), dictFormAnyOf = arrayOf("釣る", "吊る", "つる"))),
            arrayOf(TokenTemplate("", dictForm = "っつう", pos = PartOfSpeech.Particle, pin = 2798260)),
            prev = ContextCond(clauseBoundary = true)),

        // The っ/ー marks mean the という contraction, never つて "connections" or 行く forms.
        RewriteRule("ttsutte", RewritePhase.Cleanup,
            arrayOf(TokenPattern(textAnyOf = arrayOf("っつって", "っつった", "つーて", "っつー", "っつう"))),
            arrayOf(TokenTemplate("", dictForm = "っつう", pos = PartOfSpeech.Particle, pin = 2798260))),

        // --- Re-cuts (splits/merges): text is conserved, asserted at load. ---

        // 行く steals ない's い before the という contraction or quotative (できな|いっ|つー, たまんな|いっ|て).
        RewriteRule("nai-ttsuu", RewritePhase.Early,
            arrayOf(TokenPattern(text = "な", pos = arrayOf(PartOfSpeech.Auxiliary), dictFormAnyOf = arrayOf("ない")),
             TokenPattern(text = "いっ", dictFormAnyOf = arrayOf("いく", "行く")),
             TokenPattern(text = "つー", dictFormAnyOf = arrayOf("つう"))),
            arrayOf(
                TokenTemplate("ない", dictForm = "ない", normalizedForm = "ない", pos = PartOfSpeech.Auxiliary, reading = "ナイ"),
                TokenTemplate("っつー", dictForm = "っつう", normalizedForm = "っつう", pos = PartOfSpeech.Particle, reading = "ッツー", pin = 2798260),
            )),
        RewriteRule("nai-tte-mora", RewritePhase.Early,
            arrayOf(TokenPattern(text = "な", pos = arrayOf(PartOfSpeech.Auxiliary), dictFormAnyOf = arrayOf("ない")),
             TokenPattern(text = "いっ", dictFormAnyOf = arrayOf("いく", "行く")),
             TokenPattern(text = "て", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(
                TokenTemplate("ない", dictForm = "ない", normalizedForm = "ない", pos = PartOfSpeech.Auxiliary, reading = "ナイ"),
                TokenTemplate("って", dictForm = "って", normalizedForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ"),
            )),

        // Clause-initial てこ+と is the てこと (ということ) contraction; Sudachi tags it Adverb, the lever 梃子 Noun.
        RewriteRule("te-koto", RewritePhase.Early,
            arrayOf(TokenPattern(text = "てこ", pos = arrayOf(PartOfSpeech.Adverb)),
             TokenPattern(text = "と", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(
                TokenTemplate("て", dictForm = "て", normalizedForm = "て", pos = PartOfSpeech.Particle, reading = "テ"),
                TokenTemplate("こと", dictForm = "こと", normalizedForm = "こと", pos = PartOfSpeech.Noun, reading = "コト"),
            ),
            prev = ContextCond(clauseBoundary = true)),

        // しなきゃって: Sudachi reads きゃっ as a scream; it is the ければ-contraction なきゃ + quotative って.
        RewriteRule("nakya-tte", RewritePhase.Early,
            arrayOf(TokenPattern(text = "な", pos = arrayOf(PartOfSpeech.Auxiliary), dictFormAnyOf = arrayOf("だ")),
             TokenPattern(text = "きゃっ", pos = arrayOf(PartOfSpeech.Interjection)),
             TokenPattern(text = "て", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(
                TokenTemplate("なきゃ", dictForm = "なきゃ", normalizedForm = "なければ", pos = PartOfSpeech.Auxiliary, reading = "ナキャ"),
                TokenTemplate("って", dictForm = "って", normalizedForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ"),
            )),

        // ずっと+いる shreds as ずっ[ずる]|とい[Aux] (ずっといた).
        RewriteRule("zutto-i", RewritePhase.Early,
            arrayOf(TokenPattern(text = "ずっ", dictFormAnyOf = arrayOf("ずる")),
             TokenPattern(text = "とい", pos = arrayOf(PartOfSpeech.Auxiliary))),
            arrayOf(
                TokenTemplate("ずっと", dictForm = "ずっと", normalizedForm = "ずっと", pos = PartOfSpeech.Adverb, reading = "ズット"),
                TokenTemplate("い", dictForm = "いる", normalizedForm = "いる", pos = PartOfSpeech.Verb, reading = "イ"),
            )),

        // じゃ|あっ|て is じゃあ + って (じゃあってなによ); ある's common-verb protection blocks the mora-theft repair.
        RewriteRule("jaa-tte", RewritePhase.Early,
            arrayOf(TokenPattern(text = "じゃ", pos = arrayOf(PartOfSpeech.Conjunction)),
             TokenPattern(text = "あっ", dictFormAnyOf = arrayOf("ある", "会う")),
             TokenPattern(text = "て", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(
                TokenTemplate("じゃあ", dictForm = "じゃあ", normalizedForm = "じゃあ", pos = PartOfSpeech.Conjunction,
                    reading = "ジャア", pin = 1005900),
                TokenTemplate("って", dictForm = "って", normalizedForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ"),
            )),

        // Clause-final てって is て + quotative って (顔出してって……); 出てってくれ keeps the てく auxiliary.
        RewriteRule("tette-quotative", RewritePhase.Early,
            arrayOf(TokenPattern(text = "てっ", pos = arrayOf(PartOfSpeech.Auxiliary), dictFormAnyOf = arrayOf("てく")),
             TokenPattern(text = "て", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(
                TokenTemplate("て", dictForm = "て", normalizedForm = "て", pos = PartOfSpeech.Particle, reading = "テ"),
                TokenTemplate("って", dictForm = "って", normalizedForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ"),
            ),
            next = ContextCond(clauseBoundary = true)),

        // The contraction's leading っ rides on the previous token (かっ|つー); return it before combines build 買う lookalikes.
        RewriteRule("ka-ttsuu", RewritePhase.Early,
            arrayOf(TokenPattern(text = "かっ"),
             TokenPattern(text = "つー", dictFormAnyOf = arrayOf("つう"))),
            arrayOf(
                TokenTemplate("か", dictForm = "か", normalizedForm = "か", pos = PartOfSpeech.Particle, reading = "カ"),
                TokenTemplate("っつー", dictForm = "っつう", normalizedForm = "っつう", pos = PartOfSpeech.Particle, reading = "ッツー", pin = 2798260),
            )),
        RewriteRule("ka-cchuu", RewritePhase.Early,
            arrayOf(TokenPattern(text = "かっ"),
             TokenPattern(text = "ちゅう", pos = arrayOf(PartOfSpeech.Auxiliary), dictFormAnyOf = arrayOf("ちゅう"))),
            arrayOf(
                TokenTemplate("か", dictForm = "か", normalizedForm = "か", pos = PartOfSpeech.Particle, reading = "カ"),
                TokenTemplate("っちゅう", dictForm = "っちゅう", normalizedForm = "っちゅう", pos = PartOfSpeech.Conjunction, reading = "ッチュウ", pin = 2757620),
            )),
        // ども stays a Suffix so a preceding noun can reclaim it (子+ども → 子ども).
        RewriteRule("domo-ttsutte", RewritePhase.Early,
            arrayOf(TokenPattern(text = "どもっ", dictFormAnyOf = arrayOf("どもる", "吃る")),
             TokenPattern(text = "つっ", dictFormAnyOf = arrayOf("つう")),
             TokenPattern(text = "て", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(
                TokenTemplate("ども", dictForm = "ども", normalizedForm = "ども", pos = PartOfSpeech.Suffix, reading = "ドモ"),
                TokenTemplate("っつって", dictForm = "っつう", normalizedForm = "っつう", pos = PartOfSpeech.Particle, reading = "ッツッテ", pin = 2798260),
            )),

        // ねえ's え becomes an interjection before the contraction (いらね|えっ|つっ|た → え + っつった).
        RewriteRule("e-ttsutta", RewritePhase.Early,
            arrayOf(TokenPattern(text = "えっ", pos = arrayOf(PartOfSpeech.Interjection)),
             TokenPattern(text = "つっ", dictFormAnyOf = arrayOf("つう")),
             TokenPattern(text = "た", pos = arrayOf(PartOfSpeech.Auxiliary))),
            arrayOf(
                TokenTemplate("え", dictForm = "え", normalizedForm = "え", pos = PartOfSpeech.Interjection, reading = "エ"),
                TokenTemplate("っつった", dictForm = "っつう", normalizedForm = "っつう", pos = PartOfSpeech.Particle, reading = "ッツッタ", pin = 2798260),
            )),

        // ずつって: the ず arrives as the negative auxiliary, which tsutte-raw's function-word gate would accept.
        RewriteRule("zutsu-tte", RewritePhase.Early,
            arrayOf(TokenPattern(text = "ず"),
             TokenPattern(text = "つっ", dictFormAnyOf = arrayOf("つう")),
             TokenPattern(text = "て", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(
                TokenTemplate("ずつ", dictForm = "ずつ", normalizedForm = "ずつ", pos = PartOfSpeech.Particle, reading = "ズツ", pin = 2829645),
                TokenTemplate("って", dictForm = "って", normalizedForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ"),
            )),

        // Bare つっ+て/た is the contraction only after a boundary or function word; after content, つ was stolen (待|つっ|て).
        RewriteRule("tsutte-raw", RewritePhase.Early,
            arrayOf(TokenPattern(text = "つっ", dictFormAnyOf = arrayOf("つう")),
             TokenPattern(text = "て", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(TokenTemplate("つって", dictForm = "っつう", normalizedForm = "っつう", pos = PartOfSpeech.Particle, reading = "ツッテ", pin = 2798260)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb, PartOfSpeech.Noun, PartOfSpeech.CommonNoun,
                PartOfSpeech.Name, PartOfSpeech.Pronoun, PartOfSpeech.NaAdjective, PartOfSpeech.Prefix,
                PartOfSpeech.Suffix, PartOfSpeech.Numeral, PartOfSpeech.Counter), negate = true)),
        RewriteRule("tsutta-raw", RewritePhase.Early,
            arrayOf(TokenPattern(text = "つっ", dictFormAnyOf = arrayOf("つう")),
             TokenPattern(text = "た", pos = arrayOf(PartOfSpeech.Auxiliary))),
            arrayOf(TokenTemplate("つった", dictForm = "っつう", normalizedForm = "っつう", pos = PartOfSpeech.Particle, reading = "ツッタ", pin = 2798260)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb, PartOfSpeech.Noun, PartOfSpeech.CommonNoun,
                PartOfSpeech.Name, PartOfSpeech.Pronoun, PartOfSpeech.NaAdjective, PartOfSpeech.Prefix,
                PartOfSpeech.Suffix, PartOfSpeech.Numeral, PartOfSpeech.Counter), negate = true)),

        // Na-adj + だって + quote verb is copula + quotative (大袈裟だって言いたい); Cleanup, after CombineTte and verb merges.
        RewriteRule("datte-quotative", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "だって", requireUnpinned = false)),
            arrayOf(TokenTemplate("だ", dictForm = "だ", normalizedForm = "だ", pos = PartOfSpeech.Auxiliary, reading = "ダ"),
             TokenTemplate("って", dictForm = "って", normalizedForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ", pin = 2086960)),
            // Noun + だって is too often "even/too" to split (子供だって思ってる, 俺だって).
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.NaAdjective)),
            next = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb), textStartsWithAnyOf = arrayOf("言", "思", "聞", "考", "感"))),

        // Slang ねえ before quotative って shreds to ね + えっ + て after a verb (堪らねえって).
        RewriteRule("nee-tte", RewritePhase.Early,
            arrayOf(TokenPattern(text = "ね"),
             TokenPattern(text = "えっ"),   // Sudachi tags the stolen え as Interjection or Verb by context
             TokenPattern(text = "て", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(TokenTemplate("ねえ", dictForm = "ない", normalizedForm = "ない", pos = PartOfSpeech.Auxiliary, reading = "ネエ"),
             TokenTemplate("って", dictForm = "って", normalizedForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ", pin = 2086960)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb))),
)

private fun rewriteRules3(): Array<RewriteRule> = arrayOf(

        // たっちゅう after a verb is past た + dialectal という (起こしたっちゅう), not the temple noun 塔頭.
        RewriteRule("ta-cchuu", RewritePhase.Early,
            arrayOf(TokenPattern(text = "たっちゅう")),
            arrayOf(
                TokenTemplate("た", dictForm = "た", normalizedForm = "た", pos = PartOfSpeech.Auxiliary, reading = "タ"),
                TokenTemplate("っちゅう", dictForm = "っちゅう", normalizedForm = "っちゅう", pos = PartOfSpeech.Conjunction, reading = "ッチュウ", pin = 2757620),
            ),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb))),

        // じゃ + bare interjection あ is じゃあ; a genuine interjection あ after じゃ is set off by punctuation.
        RewriteRule("jaa", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "じゃ", pos = arrayOf(PartOfSpeech.Conjunction)),
             TokenPattern(text = "あ", pos = arrayOf(PartOfSpeech.Interjection))),
            arrayOf(TokenTemplate("じゃあ", dictForm = "じゃあ", normalizedForm = "じゃあ", pos = PartOfSpeech.Conjunction,
                reading = "ジャア", pin = 1005900, pinReadingIndex = 0))),

        // Kyushu や + ばい follows a full predicate; after a noun it is a cut やばい.
        RewriteRule("ya-bai", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "や", pos = arrayOf(PartOfSpeech.Auxiliary)),
             TokenPattern(text = "ばい", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(TokenTemplate("やばい", dictForm = "やばい", normalizedForm = "やばい", pos = PartOfSpeech.IAdjective,
                reading = "ヤバイ", pin = 1012840, pinReadingIndex = 0))),

        // 何かって is 何か + quotative って, not the adverb かつて.
        RewriteRule("nani-katte", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "かって", requireUnpinned = false)),
            arrayOf(
                TokenTemplate("か", dictForm = "か", normalizedForm = "か", pos = PartOfSpeech.Particle, reading = "カ"),
                TokenTemplate("って", dictForm = "って", normalizedForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ"),
            ),
            prev = ContextCond(textAnyOf = arrayOf("何", "誰", "だれ", "なん"))),

        // Kana からだ after a predicate is から + だ, not 体; a following case/topic particle means the body noun.
        RewriteRule("kara-da", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "からだ", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), requireUnpinned = false)),
            arrayOf(
                TokenTemplate("から", dictForm = "から", normalizedForm = "から", pos = PartOfSpeech.Particle, reading = "カラ"),
                TokenTemplate("だ", dictForm = "だ", normalizedForm = "だ", pos = PartOfSpeech.Auxiliary, reading = "ダ"),
            ),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb, PartOfSpeech.IAdjective, PartOfSpeech.Auxiliary)),
            next = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Particle),
                textAnyOf = arrayOf("を", "が", "に", "は", "も", "の", "で", "へ", "や"), negate = true)),

        // 思いで after an adjective is 思い + で, not the memory noun 思い出.
        RewriteRule("omoi-de", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "思いで", requireUnpinned = false)),
            arrayOf(
                TokenTemplate("思い", dictForm = "思い", normalizedForm = "思い", pos = PartOfSpeech.Noun, reading = "オモイ"),
                TokenTemplate("で", dictForm = "で", normalizedForm = "で", pos = PartOfSpeech.Particle, reading = "デ"),
            ),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.IAdjective, PartOfSpeech.Adnominal))),

        // 右手首/左手首 is 右/左 + 手首 "right/left wrist"; the lattice cuts 右手 + 首.
        RewriteRule("migi-tekubi", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "右手首", requireUnpinned = false)),
            arrayOf(
                TokenTemplate("右", dictForm = "右", normalizedForm = "右", pos = PartOfSpeech.Noun, reading = "ミギ"),
                TokenTemplate("手首", dictForm = "手首", normalizedForm = "手首", pos = PartOfSpeech.Noun, reading = "テクビ", pin = 1327770),
            )),
        RewriteRule("hidari-tekubi", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "左手首", requireUnpinned = false)),
            arrayOf(
                TokenTemplate("左", dictForm = "左", normalizedForm = "左", pos = PartOfSpeech.Noun, reading = "ヒダリ"),
                TokenTemplate("手首", dictForm = "手首", normalizedForm = "手首", pos = PartOfSpeech.Noun, reading = "テクビ", pin = 1327770),
            )),

        // 主人格 is 主 + 人格 "primary personality"; the lattice cuts 主人 + 格.
        RewriteRule("shu-jinkaku", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "主人", requireUnpinned = false), TokenPattern(text = "格")),
            arrayOf(
                TokenTemplate("主", dictForm = "主", normalizedForm = "主", pos = PartOfSpeech.Noun, reading = "シュ"),
                TokenTemplate("人格", dictForm = "人格", normalizedForm = "人格", pos = PartOfSpeech.Noun, reading = "ジンカク", pin = 1366730),
            )),

        // 存 + 在す is a shredded 存在する (存在すべく); 在す alone is the archaic honorific います.
        RewriteRule("sonzai-su", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "存", requireUnpinned = false), TokenPattern(text = "在す")),
            arrayOf(
                TokenTemplate("存在", dictForm = "存在", normalizedForm = "存在", pos = PartOfSpeech.Noun, reading = "ソンザイ"),
                TokenTemplate("す", dictForm = "する", normalizedForm = "する", pos = PartOfSpeech.Verb, reading = "ス", pin = 1157170),
            )),

        // Sentence-final ねえ can't follow 仮定形 なら, so ならねえ is 成る's slang negative (我慢ならねえ), shaped as ねえ IAdjective.
        RewriteRule("nara-nee", RewritePhase.Early,
            arrayOf(TokenPattern(text = "なら", pos = arrayOf(PartOfSpeech.Auxiliary), dictFormAnyOf = arrayOf("だ")),
             TokenPattern(text = "ねえ", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(
                TokenTemplate("なら", dictForm = "成る", normalizedForm = "成る", pos = PartOfSpeech.Verb, reading = "ナラ"),
                TokenTemplate("ねえ", dictForm = "ねえ", normalizedForm = "無い", pos = PartOfSpeech.IAdjective, reading = "ネエ"),
            )),

        // 面さ (面す) is real only before れる/せる; otherwise it is suffix 面 (づら) + さ, Early so it rejoins its noun.
        RewriteRule("tsura-sa", RewritePhase.Early,
            arrayOf(TokenPattern(text = "面さ", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("面す", "面する"))),
            arrayOf(
                TokenTemplate("面", dictForm = "面", normalizedForm = "面", pos = PartOfSpeech.Suffix, reading = "ヅラ"),
                TokenTemplate("さ", dictForm = "さ", normalizedForm = "さ", pos = PartOfSpeech.Particle, reading = "サ"),
            ),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun)),
            next = ContextCond(textAnyOf = arrayOf("れ", "れる", "れた", "せ", "せる"), negate = true)),

        // Sudachi's adverb もさ is always particle も + さ; kana 猛者 is [uk] in JMdict and would otherwise claim it.
        RewriteRule("demo-sa", RewritePhase.Early,
            arrayOf(TokenPattern(text = "で"),
             TokenPattern(text = "もさ", pos = arrayOf(PartOfSpeech.Adverb))),
            arrayOf(
                TokenTemplate("でも", dictForm = "でも", normalizedForm = "でも", pos = PartOfSpeech.Conjunction, reading = "デモ"),
                TokenTemplate("さ", dictForm = "さ", normalizedForm = "さ", pos = PartOfSpeech.Particle, reading = "サ"),
            )),
        RewriteRule("mo-sa", RewritePhase.Early,
            arrayOf(TokenPattern(text = "もさ", pos = arrayOf(PartOfSpeech.Adverb))),
            arrayOf(
                TokenTemplate("も", dictForm = "も", normalizedForm = "も", pos = PartOfSpeech.Particle, reading = "モ"),
                TokenTemplate("さ", dictForm = "さ", normalizedForm = "さ", pos = PartOfSpeech.Particle, reading = "サ"),
            )),
        RewriteRule("demo-sa-katakana", RewritePhase.Early,
            arrayOf(TokenPattern(text = "で"),
             TokenPattern(text = "もサ", pos = arrayOf(PartOfSpeech.Adverb))),
            arrayOf(
                TokenTemplate("でも", dictForm = "でも", normalizedForm = "でも", pos = PartOfSpeech.Conjunction, reading = "デモ"),
                TokenTemplate("サ", dictForm = "さ", normalizedForm = "さ", pos = PartOfSpeech.Particle, reading = "サ"),
            )),
        RewriteRule("mo-sa-katakana", RewritePhase.Early,
            arrayOf(TokenPattern(text = "もサ", pos = arrayOf(PartOfSpeech.Adverb))),
            arrayOf(
                TokenTemplate("も", dictForm = "も", normalizedForm = "も", pos = PartOfSpeech.Particle, reading = "モ"),
                TokenTemplate("サ", dictForm = "さ", normalizedForm = "さ", pos = PartOfSpeech.Particle, reading = "サ"),
            )),

        // あ directly against いつも is あいつ + も; a genuine interjection あ is set off by punctuation.
        RewriteRule("aitsu-mo", RewritePhase.Late,
            arrayOf(TokenPattern(text = "あ", pos = arrayOf(PartOfSpeech.Interjection), requireUnpinned = false),
             TokenPattern(text = "いつも")),
            arrayOf(
                TokenTemplate("あいつ", dictForm = "あいつ", normalizedForm = "あいつ", pos = PartOfSpeech.Pronoun, reading = "アイツ"),
                TokenTemplate("も", dictForm = "も", normalizedForm = "も", pos = PartOfSpeech.Particle, reading = "モ"),
            )),

        // --- Single-surface fixes at the ProcessSpecialCases position; none gate on pins. ---

        // そうかいそうかい: Sudachi mashes the middle into OOV かいそうか, which then matches 階層化.
        RewriteRule("kaisouka", RewritePhase.Early,
            arrayOf(TokenPattern(text = "かいそうか", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), requireUnpinned = false)),
            arrayOf(TokenTemplate("かい", dictForm = "かい", normalizedForm = "かい", pos = PartOfSpeech.Particle, reading = "カイ"),
             TokenTemplate("そう", dictForm = "そう", normalizedForm = "そう", pos = PartOfSpeech.Adverb, reading = "ソウ"),
             TokenTemplate("か", dictForm = "か", normalizedForm = "か", pos = PartOfSpeech.Particle, reading = "カ"))),

        // [noun]がないって: Sudachi cuts がな(仮名)+いっ(言う)+て, which resolves to the rare 我鳴る.
        RewriteRule("ganai-tte", RewritePhase.Early,
            arrayOf(TokenPattern(text = "がな", normalizedFormAnyOf = arrayOf("仮名"), requireUnpinned = false),
             TokenPattern(text = "いっ", normalizedFormAnyOf = arrayOf("言う"), requireUnpinned = false),
             TokenPattern(text = "て", requireUnpinned = false)),
            arrayOf(TokenTemplate("が", dictForm = "が", normalizedForm = "が", pos = PartOfSpeech.Particle, reading = "ガ", pin = 2028930),
             TokenTemplate("ない", dictForm = "ない", normalizedForm = "無い", pos = PartOfSpeech.IAdjective, reading = "ナイ", pin = 1529520),
             TokenTemplate("って", dictForm = "って", normalizedForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ", pin = 2086960)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun,
                PartOfSpeech.Pronoun, PartOfSpeech.Counter))),

        // After a predicate, きっての steals とき's き (→ とき+って+の); after a noun it is real (学校きっての秀才).
        RewriteRule("toki-tteno", RewritePhase.Early,
            arrayOf(TokenPattern(text = "と", requireUnpinned = false),
             TokenPattern(text = "きっての", requireUnpinned = false)),
            arrayOf(TokenTemplate("とき", dictForm = "とき", normalizedForm = "時", pos = PartOfSpeech.Noun, reading = "トキ"),
             TokenTemplate("って", dictForm = "って", normalizedForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ", pin = 2086960),
             TokenTemplate("の", dictForm = "の", normalizedForm = "の", pos = PartOfSpeech.Particle, reading = "ノ")),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb, PartOfSpeech.IAdjective,
                PartOfSpeech.Auxiliary, PartOfSpeech.Expression))),

        // Sudachi normalises most ai→ee adjectives whole, but ありがてぇ shreds to あり|が|てぇ.
        RewriteRule("arigatee", RewritePhase.Early,
            arrayOf(TokenPattern(text = "あり", requireUnpinned = false),
             TokenPattern(text = "が", requireUnpinned = false),
             TokenPattern(text = "てぇ", requireUnpinned = false)),
            arrayOf(TokenTemplate("ありがてぇ", dictForm = "ありがたい", normalizedForm = "ありがたい",
                pos = PartOfSpeech.IAdjective, reading = "アリガテェ", pin = 1541560, recoverConjugations = true))),

        // Non-standard 貸り (借り) cuts as 貸 Noun + り Aux; as a verb stem the inflection combines (貸りたい).
        RewriteRule("kariru", RewritePhase.Early,
            arrayOf(TokenPattern(text = "貸", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), requireUnpinned = false),
             TokenPattern(text = "り", requireUnpinned = false)),
            arrayOf(TokenTemplate("貸り", dictForm = "貸りる", normalizedForm = "貸りる", pos = PartOfSpeech.Verb, reading = "カリ")),
            guard = LookupGuard(LookupGuardKind.NonNameCompoundExists, "貸りる")),

        // Sudachi cuts こんなの as 連体詞 こん|なの (→ 紺); the POS gate spares the noun (色は紺なの).
        RewriteRule("konna-no", RewritePhase.Early,
            arrayOf(TokenPattern(text = "こん", pos = arrayOf(PartOfSpeech.PrenounAdjectival), requireUnpinned = false),
             TokenPattern(text = "なの", requireUnpinned = false)),
            arrayOf(TokenTemplate("こんな", dictForm = "こんな", normalizedForm = "こんな", reading = "コンナ"),
             TokenTemplate("の", dictForm = "の", normalizedForm = "の", pos = PartOfSpeech.Particle, reading = "ノ")),
            guard = LookupGuard(LookupGuardKind.CompoundExists, "こんな")),
        RewriteRule("sonna-no", RewritePhase.Early,
            arrayOf(TokenPattern(text = "そん", pos = arrayOf(PartOfSpeech.PrenounAdjectival), requireUnpinned = false),
             TokenPattern(text = "なの", requireUnpinned = false)),
            arrayOf(TokenTemplate("そんな", dictForm = "そんな", normalizedForm = "そんな", reading = "ソンナ"),
             TokenTemplate("の", dictForm = "の", normalizedForm = "の", pos = PartOfSpeech.Particle, reading = "ノ")),
            guard = LookupGuard(LookupGuardKind.CompoundExists, "そんな")),
        RewriteRule("anna-no", RewritePhase.Early,
            arrayOf(TokenPattern(text = "あん", pos = arrayOf(PartOfSpeech.PrenounAdjectival), requireUnpinned = false),
             TokenPattern(text = "なの", requireUnpinned = false)),
            arrayOf(TokenTemplate("あんな", dictForm = "あんな", normalizedForm = "あんな", reading = "アンナ"),
             TokenTemplate("の", dictForm = "の", normalizedForm = "の", pos = PartOfSpeech.Particle, reading = "ノ")),
            guard = LookupGuard(LookupGuardKind.CompoundExists, "あんな")),
        RewriteRule("donna-no", RewritePhase.Early,
            arrayOf(TokenPattern(text = "どん", pos = arrayOf(PartOfSpeech.PrenounAdjectival), requireUnpinned = false),
             TokenPattern(text = "なの", requireUnpinned = false)),
            arrayOf(TokenTemplate("どんな", dictForm = "どんな", normalizedForm = "どんな", reading = "ドンナ"),
             TokenTemplate("の", dictForm = "の", normalizedForm = "の", pos = PartOfSpeech.Particle, reading = "ノ")),
            guard = LookupGuard(LookupGuardKind.CompoundExists, "どんな")),

        // Sudachi cuts ども+め after a noun as prefix ど + もめ (揉め) (化け物どもめ).
        RewriteRule("domo-me", RewritePhase.Early,
            arrayOf(TokenPattern(text = "ど", pos = arrayOf(PartOfSpeech.Prefix), requireUnpinned = false),
             TokenPattern(text = "もめ", requireUnpinned = false)),
            arrayOf(TokenTemplate("ども", dictForm = "ども", normalizedForm = "ども", pos = PartOfSpeech.Suffix, reading = "ドモ"),
             TokenTemplate("め", dictForm = "め", normalizedForm = "め", pos = PartOfSpeech.Suffix, reading = "メ")),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun,
                PartOfSpeech.Name, PartOfSpeech.Pronoun))),

        // Katakana particles ノヨ, not the name 乃代.
        RewriteRule("no-yo", RewritePhase.Early,
            arrayOf(TokenPattern(text = "ノヨ", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.NaAdjective), requireUnpinned = false)),
            arrayOf(TokenTemplate("ノ", dictForm = "の", normalizedForm = "の", pos = PartOfSpeech.Particle, reading = "ノ"),
             TokenTemplate("ヨ", dictForm = "よ", normalizedForm = "よ", pos = PartOfSpeech.Particle, reading = "ヨ"))),

        // Clause-initial いいか、/いいか! is "Listen!"; the question keeps its split elsewhere (行っていいか分からない, いいか?).
        RewriteRule("iika-listen", RewritePhase.Early,
            arrayOf(TokenPattern(text = "いい", pos = arrayOf(PartOfSpeech.IAdjective), requireUnpinned = false),
             TokenPattern(text = "か", pos = arrayOf(PartOfSpeech.Particle), requireUnpinned = false)),
            arrayOf(TokenTemplate("いいか", dictForm = "いいか", normalizedForm = "いいか", pos = PartOfSpeech.Expression,
                reading = "イイカ", pin = 2555520)),
            prev = ContextCond(clauseBoundary = true),
            next = ContextCond(textAnyOf = arrayOf("、", "!", "！"))),

        // Clause-initial ですが/ですけど are polite conjunctions ("however"); with no predicate before them they cannot be copula + particle.
        RewriteRule("desuga-conj", RewritePhase.Early,
            arrayOf(TokenPattern(text = "です", pos = arrayOf(PartOfSpeech.Auxiliary)),
             TokenPattern(text = "が", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(TokenTemplate("ですが", dictForm = "ですが", normalizedForm = "ですが", pos = PartOfSpeech.Conjunction,
                reading = "デスガ", pin = 2850805)),
            prev = ContextCond(clauseBoundary = true)),
        RewriteRule("desukedo-conj", RewritePhase.Early,
            arrayOf(TokenPattern(text = "です", pos = arrayOf(PartOfSpeech.Auxiliary)),
             TokenPattern(text = "けど", pos = arrayOf(PartOfSpeech.Particle))),
            arrayOf(TokenTemplate("ですけど", dictForm = "ですけど", normalizedForm = "ですけど", pos = PartOfSpeech.Conjunction,
                reading = "デスケド", pin = 2871534)),
            prev = ContextCond(clauseBoundary = true)),

        // Noun ひと with lemma 一 is almost always 人; prefix uses (ひと月) are tagged 接頭辞 or stay fused.
        RewriteRule("hito-dict", RewritePhase.Early,
            arrayOf(TokenPattern(text = "ひと", pos = arrayOf(PartOfSpeech.Noun), dictFormAnyOf = arrayOf("一"), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "人", normalizedForm = "人"))),
        RewriteRule("hito-norm", RewritePhase.Early,
            arrayOf(TokenPattern(text = "ひと", pos = arrayOf(PartOfSpeech.Noun), normalizedFormAnyOf = arrayOf("一"), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "人", normalizedForm = "人"))),

        // Suffix かねる + negative is the lexicalised かねない (破りかねない); df=かねる rules out 金.
        RewriteRule("kanenai", RewritePhase.Early,
            arrayOf(TokenPattern(text = "かね", pos = arrayOf(PartOfSpeech.Suffix), dictFormAnyOf = arrayOf("かねる"), requireUnpinned = false),
             TokenPattern(text = "ない", requireUnpinned = false)),
            arrayOf(TokenTemplate("かねない", dictForm = "かねない", normalizedForm = "かねない",
                pos = PartOfSpeech.Expression, reading = "カネナイ"))),
        RewriteRule("kanenakatta", RewritePhase.Early,
            arrayOf(TokenPattern(text = "かね", pos = arrayOf(PartOfSpeech.Suffix), dictFormAnyOf = arrayOf("かねる"), requireUnpinned = false),
             TokenPattern(text = "なかっ", requireUnpinned = false),
             TokenPattern(text = "た", requireUnpinned = false)),
            arrayOf(TokenTemplate("かねなかった", dictForm = "かねない", normalizedForm = "かねない",
                pos = PartOfSpeech.Expression, reading = "カネナカッタ"))),

        // Imperative たまえ after a verb stem (気をつけたまえ); た前 is ungrammatical ("before" is る前/の前).
        RewriteRule("tamae", RewritePhase.Early,
            arrayOf(TokenPattern(text = "た", pos = arrayOf(PartOfSpeech.Auxiliary), requireUnpinned = false),
             TokenPattern(text = "まえ", pos = arrayOf(PartOfSpeech.Noun), requireUnpinned = false)),
            arrayOf(TokenTemplate("たまえ", dictForm = "たまえ", normalizedForm = "たまえ", pos = PartOfSpeech.Suffix, reading = "タマエ")),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb))),

        // Suffix-tagged っけ is the recollection sentence-ending particle.
        RewriteRule("kke-sfp", RewritePhase.Early,
            arrayOf(TokenPattern(text = "っけ", pos = arrayOf(PartOfSpeech.Suffix), requireUnpinned = false)),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Particle, posSection = PartOfSpeechSection.SentenceEndingParticle))),

        // 私戦 + いたく(痛く) is 私 + 戦いたく, not しせん "private war".
        RewriteRule("shisen-itaku", RewritePhase.Early,
            arrayOf(TokenPattern(text = "私戦", pos = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun), requireUnpinned = false),
             TokenPattern(text = "いたく", normalizedFormAnyOf = arrayOf("痛く"), requireUnpinned = false)),
            arrayOf(TokenTemplate("私", dictForm = "私", normalizedForm = "私", pos = PartOfSpeech.Pronoun, reading = "ワタシ"),
             TokenTemplate("戦いたく", dictForm = "戦う", normalizedForm = "戦う", pos = PartOfSpeech.Verb, reading = "タタカイタク"))),

        // Sudachi glues a trailing particle into these 表現 tokens.
        RewriteRule("hitomae-de", RewritePhase.Early,
            arrayOf(TokenPattern(text = "人前で", pos = arrayOf(PartOfSpeech.Expression), requireUnpinned = false)),
            arrayOf(TokenTemplate("人前", dictForm = "人前", normalizedForm = "人前", pos = PartOfSpeech.Noun, reading = ""),
             TokenTemplate("で", dictForm = "で", normalizedForm = "で", pos = PartOfSpeech.Particle, reading = "デ"))),
        RewriteRule("sama-ni", RewritePhase.Early,
            arrayOf(TokenPattern(text = "様に", pos = arrayOf(PartOfSpeech.Expression), requireUnpinned = false)),
            arrayOf(TokenTemplate("様", dictForm = "様", normalizedForm = "様", pos = PartOfSpeech.Noun, reading = ""),
             TokenTemplate("に", dictForm = "に", normalizedForm = "に", pos = PartOfSpeech.Particle, reading = "ニ"))),
        RewriteRule("dare-datte", RewritePhase.Early,
            arrayOf(TokenPattern(text = "誰だって", pos = arrayOf(PartOfSpeech.Expression), requireUnpinned = false)),
            arrayOf(TokenTemplate("誰", dictForm = "誰", normalizedForm = "誰", pos = PartOfSpeech.Pronoun, reading = ""),
             TokenTemplate("だって", dictForm = "だって", normalizedForm = "だって", pos = PartOfSpeech.Particle, reading = "ダッテ"))),

        // Split so a preceding 放って can form 放っておく.
        RewriteRule("okeba-ii", RewritePhase.Early,
            arrayOf(TokenPattern(text = "おけばいい", pos = arrayOf(PartOfSpeech.Expression), requireUnpinned = false)),
            arrayOf(TokenTemplate("おけ", dictForm = "おく", normalizedForm = "おく", pos = PartOfSpeech.Verb, reading = "オケ"),
             TokenTemplate("ば", dictForm = "ば", normalizedForm = "ば", pos = PartOfSpeech.Particle, reading = "バ"),
             TokenTemplate("いい", dictForm = "いい", normalizedForm = "いい", pos = PartOfSpeech.IAdjective, reading = "イイ"))),
)

private fun rewriteRules4(): Array<RewriteRule> = arrayOf(

        // Suffix-tagged 張り before ます/やがる is 張る's stem (結界張ります, 意地ばっかり張りやがって).
        RewriteRule("hari-verb-stem", RewritePhase.Early,
            arrayOf(TokenPattern(text = "張り", pos = arrayOf(PartOfSpeech.Suffix))),
            arrayOf(TokenTemplate("", dictForm = "張る", normalizedForm = "張る", pos = PartOfSpeech.Verb)),
            next = ContextCond(textStartsWithAnyOf = arrayOf("ます", "まし", "ませ", "やが"))),

        // Sudachi marks the 心 of 心ばかり "small token (of thanks)" as a prefix; the noun 心 + ばかり stays split.
        RewriteRule("kokoro-bakari", RewritePhase.Early,
            arrayOf(TokenPattern(text = "心", pos = arrayOf(PartOfSpeech.Prefix)),
             TokenPattern(text = "ばかり")),
            arrayOf(TokenTemplate("心ばかり", dictForm = "心ばかり", normalizedForm = "心ばかり", pos = PartOfSpeech.Noun,
                reading = "ココロバカリ", pin = 1793680)),
            next = ContextCond(textAnyOf = arrayOf("が", "は", "を", "も"), negate = true)),

        // 結構人 "good-natured person" is almost always the adverb 結構 + 人 (結構人がいる).
        RewriteRule("kekkou-hito", RewritePhase.Early,
            arrayOf(TokenPattern(text = "結構人", requireUnpinned = false)),
            arrayOf(TokenTemplate("結構", dictForm = "結構", normalizedForm = "結構", pos = PartOfSpeech.Adverb, reading = "ケッコウ", pin = 1254760, hardPin = true),
             TokenTemplate("人", dictForm = "人", normalizedForm = "人", pos = PartOfSpeech.Noun, reading = "ヒト"))),

        // で + noun しょう is a split でしょう.
        RewriteRule("de-shou", RewritePhase.Early,
            arrayOf(TokenPattern(text = "で", pos = arrayOf(PartOfSpeech.Conjunction, PartOfSpeech.Auxiliary), requireUnpinned = false),
             TokenPattern(text = "しょう", pos = arrayOf(PartOfSpeech.Noun), requireUnpinned = false)),
            arrayOf(TokenTemplate("でしょう", dictForm = "でしょう", normalizedForm = "です", pos = PartOfSpeech.Expression,
                posSection = PartOfSpeechSection.None, reading = "デショウ"))),

        // Sudachi parses 何でしょう as 何で(noun) + しょう(noun).
        RewriteRule("nande-shou", RewritePhase.Early,
            arrayOf(TokenPattern(text = "何で", pos = arrayOf(PartOfSpeech.Noun), requireUnpinned = false),
             TokenPattern(text = "しょう", pos = arrayOf(PartOfSpeech.Noun), requireUnpinned = false)),
            arrayOf(TokenTemplate("何", dictForm = "何", normalizedForm = "何", pos = PartOfSpeech.Pronoun, reading = "ナニ"),
             TokenTemplate("でしょう", dictForm = "でしょう", normalizedForm = "です", pos = PartOfSpeech.Expression,
                posSection = PartOfSpeechSection.None, reading = "デショウ"))),

        // Sudachi sometimes tags でしょう Auxiliary.
        RewriteRule("deshou-expression", RewritePhase.Early,
            arrayOf(TokenPattern(text = "でしょう", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Expression, posSection = PartOfSpeechSection.None))),

        // Standalone ぬ misread as the archaic verb 寝(ぬ) is the classical negative auxiliary (norm ず).
        RewriteRule("nu-negative", RewritePhase.Early,
            arrayOf(TokenPattern(text = "ぬ", pos = arrayOf(PartOfSpeech.Verb), normalizedFormAnyOf = arrayOf("寝る"), requireUnpinned = false)),
            arrayOf(TokenTemplate("", normalizedForm = "ず", pos = PartOfSpeech.Auxiliary))),

        // なれ tagged 汝 (archaic "thou") is 成る's 命令形/可能形 stem in modern text.
        RewriteRule("nare-naru", RewritePhase.Early,
            arrayOf(TokenPattern(text = "なれ", pos = arrayOf(PartOfSpeech.Pronoun), normalizedFormAnyOf = arrayOf("汝"), requireUnpinned = false)),
            arrayOf(TokenTemplate("", dictForm = "なる", normalizedForm = "なる", pos = PartOfSpeech.Verb, reading = "ナレ"))),

        RewriteRule("ima-adverb", RewritePhase.Early,
            arrayOf(TokenPattern(text = "今", pos = arrayOf(PartOfSpeech.Prefix), requireUnpinned = false)),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Adverb))),

        // Modern 空 almost always reads から; うつろ is usually written 虚ろ.
        RewriteRule("kara-empty", RewritePhase.Early,
            arrayOf(TokenPattern(text = "空", pos = arrayOf(PartOfSpeech.NaAdjective), readingPrefix = "ウツロ", requireUnpinned = false)),
            arrayOf(TokenTemplate("", normalizedForm = "空", pos = PartOfSpeech.Noun, reading = "カラ"))),

        // にしろ ("even if"), except after a noun-ish host (大概にしろ is 大概にする's imperative).
        RewriteRule("ni-shiro", RewritePhase.Early,
            arrayOf(TokenPattern(text = "に", requireUnpinned = false),
             TokenPattern(text = "しろ", requireUnpinned = false)),
            arrayOf(TokenTemplate("にしろ", dictForm = "にしろ", normalizedForm = "にしろ", pos = PartOfSpeech.Expression, reading = "ニシロ")),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.NaAdjective, PartOfSpeech.Pronoun), negate = true)),

        // とくと after a verb is とく (ておく) + と, not the adverb 篤と.
        RewriteRule("toku-to", RewritePhase.Early,
            arrayOf(TokenPattern(text = "とくと", pos = arrayOf(PartOfSpeech.Adverb), normalizedFormAnyOf = arrayOf("篤と"), requireUnpinned = false)),
            arrayOf(TokenTemplate("とく", dictForm = "とく", normalizedForm = "とく", pos = PartOfSpeech.Auxiliary, reading = "トク"),
             TokenTemplate("と", dictForm = "と", normalizedForm = "と", pos = PartOfSpeech.Particle, reading = "ト")),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb))),

        // とくよう is とく (ておく) + よう, not 徳用; noun hosts count since Sudachi often tags verb stems as nouns.
        RewriteRule("toku-you", RewritePhase.Early,
            arrayOf(TokenPattern(text = "とくよう", normalizedFormAnyOf = arrayOf("徳用"), requireUnpinned = false)),
            arrayOf(TokenTemplate("とく", dictForm = "とく", normalizedForm = "とく", pos = PartOfSpeech.Auxiliary, reading = "トク"),
             TokenTemplate("よう", dictForm = "よう", normalizedForm = "よう", pos = PartOfSpeech.Noun, reading = "ヨウ")),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Verb, PartOfSpeech.Noun))),

        RewriteRule("shiyou-toshite", RewritePhase.Early,
            arrayOf(TokenPattern(text = "しよう", pos = arrayOf(PartOfSpeech.Noun), requireUnpinned = false),
             TokenPattern(text = "として", requireUnpinned = false)),
            arrayOf(TokenTemplate("しようとして", dictForm = "しようとする", pos = PartOfSpeech.Verb, reading = "シヨウトシテ"))),
        // Sudachi sometimes misreads し as a conjunction here.
        RewriteRule("shi-youtoshite", RewritePhase.Early,
            arrayOf(TokenPattern(text = "し", pos = arrayOf(PartOfSpeech.Conjunction), requireUnpinned = false),
             TokenPattern(text = "ようとして", requireUnpinned = false)),
            arrayOf(TokenTemplate("しようとして", dictForm = "しようとして", pos = PartOfSpeech.Verb, reading = "シヨウトシテ"))),

        // 恋って is 恋 + quotative って, not archaic 恋う.
        RewriteRule("koi-tte", RewritePhase.Early,
            arrayOf(TokenPattern(text = "恋っ", pos = arrayOf(PartOfSpeech.Verb), dictFormAnyOf = arrayOf("恋う"), requireUnpinned = false),
             TokenPattern(text = "て", pos = arrayOf(PartOfSpeech.Particle), requireUnpinned = false)),
            arrayOf(TokenTemplate("恋", dictForm = "恋", normalizedForm = "恋", pos = PartOfSpeech.Noun, reading = "コイ"),
             TokenTemplate("って", dictForm = "って", normalizedForm = "って", pos = PartOfSpeech.Particle, reading = "ッテ"))),

        // 逆 + copula に is almost always the adverb 逆に ("conversely").
        RewriteRule("gyaku-ni", RewritePhase.Early,
            arrayOf(TokenPattern(text = "逆", pos = arrayOf(PartOfSpeech.Noun), requireUnpinned = false),
             TokenPattern(text = "に", dictFormAnyOf = arrayOf("だ"), requireUnpinned = false)),
            arrayOf(TokenTemplate("逆に", dictForm = "逆に", pos = PartOfSpeech.Adverb, reading = "ギャクニ"))),

        RewriteRule("you-noun", RewritePhase.Early,
            arrayOf(TokenPattern(text = "よう", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Noun))),
        RewriteRule("juugo-numeral", RewritePhase.Early,
            arrayOf(TokenPattern(text = "十五", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Numeral))),
        RewriteRule("ore-pronoun", RewritePhase.Early,
            arrayOf(TokenPattern(text = "オレ", requireUnpinned = false)),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Pronoun))),

        // 飛んだ after a case particle is 飛ぶ past (意識が飛んだ); the 連体詞 とんだ only opens a noun phrase.
        RewriteRule("tonda-tobu", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "飛んだ")),
            arrayOf(TokenTemplate("", dictForm = "飛ぶ", normalizedForm = "飛ぶ", pos = PartOfSpeech.Verb, pin = 1429700, recoverConjugations = true)),
            prev = ContextCond(textAnyOf = arrayOf("が", "は", "も", "に", "を", "で", "へ", "から", "まで"))),

        // 置き after a case particle is 置く's 連用形 (横に置き、); the interval suffix おき follows a quantity (三日置き).
        RewriteRule("oki-oku", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "置き")),
            arrayOf(TokenTemplate("", dictForm = "置く", normalizedForm = "置く", pos = PartOfSpeech.Verb, pin = 1421850, recoverConjugations = true)),
            prev = ContextCond(textAnyOf = arrayOf("に", "を", "へ"))),

        // Sudachi shreds 強がって into 強/がっ/て; conjugated forms only, since bare 強がり is the noun.
        RewriteRule("tsuyogaru", RewritePhase.Cleanup,
            arrayOf(TokenPattern(textAnyOf = arrayOf("強がって", "強がった"))),
            arrayOf(TokenTemplate("", dictForm = "強がる", normalizedForm = "強がる", pos = PartOfSpeech.Verb, pin = 1928800, recoverConjugations = true))),

        // 強がり before a particle is the noun "bluff"; the がる chain would resolve it to 強い (ながら keeps the verb).
        RewriteRule("tsuyogari-noun", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "強がり")),
            arrayOf(TokenTemplate("", dictForm = "強がり", normalizedForm = "強がり", pos = PartOfSpeech.Noun, pin = 1236110)),
            next = ContextCond(textAnyOf = arrayOf("を", "が", "は", "の", "で"))),

        // 放ってお* is the expression 放っておく, not bare 放る.
        RewriteRule("hotteoku", RewritePhase.Cleanup,
            arrayOf(TokenPattern(textStartsWith = "放ってお")),
            arrayOf(TokenTemplate("", dictForm = "放っておく", normalizedForm = "放っておく", pin = 1907980, recoverConjugations = true))),

        // まさに never takes a topic particle, so 将に right before は is the noun 将 + に (将には必要な資質).
        RewriteRule("sho-ni", RewritePhase.Cleanup,
            arrayOf(TokenPattern(text = "将に", pos = arrayOf(PartOfSpeech.Adverb))),
            arrayOf(TokenTemplate("将", dictForm = "将", normalizedForm = "将", pos = PartOfSpeech.Noun, reading = "ショウ"),
             TokenTemplate("に", dictForm = "に", normalizedForm = "に", pos = PartOfSpeech.Particle,
                 posSection = PartOfSpeechSection.CaseMarkingParticle, reading = "ニ")),
            next = ContextCond(textAnyOf = arrayOf("は"), posAnyOf = arrayOf(PartOfSpeech.Particle))),

        // 表 before へ/に with no noun host is おもて (表へ出る), not the chart ひょう (メニュー表).
        RewriteRule("omote", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "表", readingPrefix = "ヒョウ")),
            arrayOf(TokenTemplate("", reading = "オモテ")),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun), negate = true),
            next = ContextCond(textAnyOf = arrayOf("へ", "に"))),
        // 一日 is ついたち only as a date after a month (X月一日).
        RewriteRule("ichinichi", RewritePhase.Reading,
            arrayOf(TokenPattern(textAnyOf = arrayOf("一日", "１日", "1日"), readingPrefix = "ツイタチ")),
            arrayOf(TokenTemplate("", reading = "イチニチ")),
            prev = ContextCond(textEndsWithAnyOf = arrayOf("月"), negate = true)),
        // Compound-only readings: 禍 カ (コロナ禍), 私 シ (私立), 次 ジ (次回), 隙 ヒマ (obsolete).
        RewriteRule("wazawai", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "禍", readingPrefix = "カ")),
            arrayOf(TokenTemplate("", reading = "ワザワイ"))),
        RewriteRule("watashi", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "私", readingPrefix = "シ")),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Pronoun, reading = "ワタシ"))),
        RewriteRule("tsugi", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "次", pos = arrayOf(PartOfSpeech.Prefix), readingPrefix = "ジ")),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.CommonNoun, reading = "ツギ"))),
        RewriteRule("suki", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "隙", readingPrefix = "ヒマ")),
            arrayOf(TokenTemplate("", reading = "スキ"))),
        // Rare name/archaic readings: 全機 まさき, 何時 なんどき, 大仰 おおのき.
        RewriteRule("zenki", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "全機", readingPrefix = "マサキ")),
            arrayOf(TokenTemplate("", reading = "ゼンキ"))),
        RewriteRule("nanji", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "何時", readingPrefix = "ナンドキ")),
            arrayOf(TokenTemplate("", reading = "ナンジ"))),
        RewriteRule("oogyou", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "大仰", readingPrefix = "オオノキ")),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.NaAdjective, reading = "オオギョウ"))),
        // Suffix 長 チョウ is the noun "chief" (1429740); JMDict's suffix 長 is なが "long".
        RewriteRule("chou-chief", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "長", pos = arrayOf(PartOfSpeech.Suffix), readingPrefix = "チョウ")),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Noun))),
        // 通り right after a noun is the suffix どおり "as per" (予定通り), not the street noun.
        RewriteRule("doori", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "通り", readingPrefix = "トオリ")),
            arrayOf(TokenTemplate("", pin = 1432930)),
            prev = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.CommonNoun))),
        // 縁 after の with a rimmed object is ふち "rim" (浴槽の縁), not えん "fate".
        RewriteRule("fuchi", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "縁", readingPrefix = "エン")),
            arrayOf(TokenTemplate("", reading = "フチ")),
            prev = ContextCond(textAnyOf = arrayOf("の")),
            window = WindowCond(-2, -2, textAnyOf = arrayOf("浴槽", "風呂", "プール", "池", "井戸", "窓", "テーブル",
                "机", "帽子", "コップ", "グラス", "崖", "屋根", "ベッド"))),
        // 生 after 次の/前の… is せい "life" (次の生); なま lists bare 生 with priority, so the reading alone loses.
        RewriteRule("sei-life", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "生", readingPrefix = "ナマ")),
            arrayOf(TokenTemplate("", reading = "セイ", pin = 2088240)),
            prev = ContextCond(textAnyOf = arrayOf("の")),
            window = WindowCond(-2, -2, textAnyOf = arrayOf("次", "前", "今", "来", "この", "別"))),
        // 糞 is くそ unless it reads as literal droppings: after の (犬の糞) or before と (糞と尿).
        RewriteRule("kuso", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "糞", readingPrefix = "フン")),
            arrayOf(TokenTemplate("", reading = "クソ")),
            prev = ContextCond(textAnyOf = arrayOf("の"), negate = true),
            next = ContextCond(textAnyOf = arrayOf("と"), negate = true)),
        // Sudachi's あの tags are unreliable: 感動詞 is treated as prenominal, 連体詞 as the filler when no nominal follows.
        RewriteRule("ano-prenoun", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "あの", pos = arrayOf(PartOfSpeech.Interjection))),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.PrenounAdjectival))),
        RewriteRule("ano-filler", RewritePhase.Reading,
            arrayOf(TokenPattern(text = "あの", pos = arrayOf(PartOfSpeech.PrenounAdjectival))),
            arrayOf(TokenTemplate("", pos = PartOfSpeech.Interjection)),
            next = ContextCond(posAnyOf = arrayOf(PartOfSpeech.Noun, PartOfSpeech.Pronoun, PartOfSpeech.NaAdjective,
                PartOfSpeech.Counter, PartOfSpeech.Numeral), negate = true)),
)

private class RewriteIndex {
    val byFirstText = HashMap<String, MutableList<RewriteRule>>()
    // First pattern has no exact surface (StartsWith/EndsWith/POS-only), so these are tried at every token.
    val residual = mutableListOf<RewriteRule>()
    val isEmpty get() = byFirstText.isEmpty() && residual.isEmpty()
}

private val rewriteIndex: Map<RewritePhase, RewriteIndex> by lazy { buildRewriteIndex(rewriteRulesTable) }

private fun buildRewriteIndex(rules: Array<RewriteRule>): Map<RewritePhase, RewriteIndex> {
    validateRewriteRules(rules)
    val index = HashMap<RewritePhase, RewriteIndex>()
    for (rule in rules) {
        val bucket = index.getOrPut(rule.phase) { RewriteIndex() }
        val first = rule.match[0]
        if (first.text != null)
            bucket.byFirstText.getOrPut(first.text) { mutableListOf() }.add(rule)
        else if (!first.textAnyOf.isNullOrEmpty())
            for (t in first.textAnyOf) bucket.byFirstText.getOrPut(t) { mutableListOf() }.add(rule)
        else
            bucket.residual.add(rule)
    }
    return index
}

// Fails fast at first use on duplicate ids, bad arity, and literal split/merge rules that don't conserve text.
private fun validateRewriteRules(rules: Array<RewriteRule>) {
    val seenIds = HashSet<String>()
    for (rule in rules) {
        if (!seenIds.add(rule.id))
            throw IllegalStateException("Duplicate rewrite rule id '${rule.id}'.")
        if (rule.match.size !in 1..3)
            throw IllegalStateException("Rewrite rule '${rule.id}' must match 1–3 tokens.")
        if (rule.replace.isEmpty())
            throw IllegalStateException("Rewrite rule '${rule.id}' must produce at least one token.")

        // Only all-literal rules are checkable here; TextAnyOf/StartsWith and Text="" are verified at runtime.
        val literalMatch = rule.match.all { it.text != null }
        val literalReplace = rule.replace.all { it.text.isNotEmpty() }
        if (literalMatch && literalReplace) {
            val matched = rule.match.joinToString("") { it.text!! }
            val produced = rule.replace.joinToString("") { it.text }
            if (matched != produced)
                throw IllegalStateException(
                    "Rewrite rule '${rule.id}' does not conserve surface text: '$matched' -> '$produced'.")
        }

        // Splits/merges need an explicit reading per output.
        val isRewrite = rule.replace.size != 1 || rule.replace[0].text.isNotEmpty()
        if (isRewrite)
            for (t in rule.replace)
                if (t.text.isNotEmpty() && t.reading == null)
                    throw IllegalStateException(
                        "Rewrite rule '${rule.id}' output '${t.text}' must specify a Reading (splits/merges cannot infer it).")
    }
}

internal fun MorphologicalAnalyser.applyTokenRewriteRulesEarly(words: MutableList<WordInfo>): MutableList<WordInfo> =
    applyTokenRewriteRules(words, RewritePhase.Early)

internal fun MorphologicalAnalyser.applyTokenRewriteRulesLate(words: MutableList<WordInfo>): MutableList<WordInfo> =
    applyTokenRewriteRules(words, RewritePhase.Late)

internal fun MorphologicalAnalyser.applyTokenRewriteRulesCleanup(words: MutableList<WordInfo>): MutableList<WordInfo> =
    applyTokenRewriteRules(words, RewritePhase.Cleanup)

internal fun MorphologicalAnalyser.applyTokenRewriteRulesReading(words: MutableList<WordInfo>): MutableList<WordInfo> =
    applyTokenRewriteRules(words, RewritePhase.Reading)

private fun MorphologicalAnalyser.applyTokenRewriteRules(
    wordInfos: MutableList<WordInfo>, phase: RewritePhase,
): MutableList<WordInfo> {
    val index = rewriteIndex[phase]
    if (index == null || index.isEmpty) return wordInfos
    return runRewriteRules(wordInfos, index)
}

// Returns the input list when nothing fires, so the pipeline skips a rescan; exact bucket, then residual, in table order.
private fun MorphologicalAnalyser.runRewriteRules(wordInfos: MutableList<WordInfo>, index: RewriteIndex): MutableList<WordInfo> {
    var result: MutableList<WordInfo>? = null
    var i = 0
    while (i < wordInfos.size) {
        val rule = findFiringRule(wordInfos, i, index)
        if (rule != null) {
            if (result == null) result = ArrayList(wordInfos.subList(0, i))
            result.addAll(buildOutputs(rule, wordInfos, i))
            i += rule.match.size
        } else {
            result?.add(wordInfos[i])
            i++
        }
    }
    return result ?: wordInfos
}

private fun MorphologicalAnalyser.findFiringRule(tokens: List<WordInfo>, i: Int, index: RewriteIndex): RewriteRule? {
    index.byFirstText[tokens[i].text]?.let { exact ->
        for (rule in exact) if (matchesRuleAt(rule, tokens, i)) return rule
    }
    for (rule in index.residual) if (matchesRuleAt(rule, tokens, i)) return rule
    return null
}

private fun MorphologicalAnalyser.matchesRuleAt(rule: RewriteRule, tokens: List<WordInfo>, i: Int): Boolean {
    val len = rule.match.size
    if (i + len > tokens.size) return false

    for (k in 0 until len) if (!matchesPattern(tokens[i + k], rule.match[k])) return false

    if (rule.prev != null && !matchesContext(if (i > 0) tokens[i - 1] else null, rule.prev)) return false

    val nextIdx = i + len
    if (rule.next != null && !matchesContext(if (nextIdx < tokens.size) tokens[nextIdx] else null, rule.next)) return false

    if (rule.window != null && !matchesWindow(tokens, i, rule.window)) return false

    if (rule.guard != null && !evaluateGuard(rule.guard, tokens, i, len)) return false

    return true
}

private fun matchesPattern(token: WordInfo, p: TokenPattern): Boolean {
    if (p.requireUnpinned && token.preMatchedWordId != null) return false
    if (p.text != null && token.text != p.text) return false
    if (p.textAnyOf != null && token.text !in p.textAnyOf) return false
    if (p.textStartsWith != null && !token.text.startsWith(p.textStartsWith)) return false
    if (p.textEndsWith != null && !token.text.endsWith(p.textEndsWith)) return false
    if (p.pos != null && token.partOfSpeech !in p.pos) return false
    if (p.dictFormAnyOf != null && token.dictionaryForm !in p.dictFormAnyOf) return false
    if (p.normalizedFormAnyOf != null && token.normalizedForm !in p.normalizedFormAnyOf) return false
    if (p.readingPrefix != null && !token.reading.startsWith(p.readingPrefix)) return false
    if (p.notReadingPrefix != null && token.reading.startsWith(p.notReadingPrefix)) return false
    return true
}

private fun matchesContext(neighbour: WordInfo?, cond: ContextCond): Boolean {
    var ok = true
    if (cond.textAnyOf != null)
        ok = ok and (neighbour != null && neighbour.text in cond.textAnyOf)
    if (cond.textEndsWithAnyOf != null)
        ok = ok and (neighbour != null && cond.textEndsWithAnyOf.any { neighbour.text.endsWith(it) })
    if (cond.textStartsWithAnyOf != null)
        ok = ok and (neighbour != null && cond.textStartsWithAnyOf.any { neighbour.text.startsWith(it) })
    if (cond.posAnyOf != null)
        ok = ok and (neighbour != null && neighbour.partOfSpeech in cond.posAnyOf)
    if (cond.numeral)
        ok = ok and (neighbour != null && isNumeralToken(neighbour))
    if (cond.clauseBoundary)
        ok = ok and isClauseBoundary(neighbour)
    return if (cond.negate) !ok else ok
}

// Shared (Disambiguation uses it).
internal fun isClauseBoundary(neighbour: WordInfo?): Boolean =
    neighbour == null || neighbour.partOfSpeech == PartOfSpeech.Symbol ||
        neighbour.partOfSpeech == PartOfSpeech.SupplementarySymbol || neighbour.partOfSpeech == PartOfSpeech.BlankSpace

private fun matchesWindow(tokens: List<WordInfo>, matchStart: Int, cond: WindowCond): Boolean {
    val from = maxOf(0, matchStart + cond.from)
    val to = minOf(tokens.size - 1, matchStart + cond.to)
    var found = false
    for (j in from..to) {
        val t = tokens[j]
        if ((cond.textAnyOf == null || t.text in cond.textAnyOf) &&
            (cond.posAnyOf == null || t.partOfSpeech in cond.posAnyOf)) {
            found = true
            break
        }
    }
    return if (cond.negate) !found else found
}

private fun MorphologicalAnalyser.evaluateGuard(guard: LookupGuard, tokens: List<WordInfo>, i: Int, len: Int): Boolean {
    val expanded = expandGuardPattern(guard.pattern, tokens, i, len)
    return when (guard.kind) {
        LookupGuardKind.CompoundExists -> hasCompoundLookup?.invoke(expanded) == true
        LookupGuardKind.NonNameCompoundExists -> hasNonNameCompoundLookup?.invoke(expanded) == true
        LookupGuardKind.CompoundAbsent -> hasCompoundLookup?.invoke(expanded) != true
        LookupGuardKind.FrequencyRankUnder ->
            getNonNameCompoundFrequencyRank?.invoke(expanded)?.let { it < (guard.rank ?: Int.MAX_VALUE) } == true
    }
}

// "{k}" expands to the k-th matched surface; any other character is literal ("貸りる", "{0}い").
private fun expandGuardPattern(pattern: String, tokens: List<WordInfo>, i: Int, len: Int): String {
    if ('{' !in pattern) return pattern
    val sb = StringBuilder(pattern.length + 4)
    var p = 0
    while (p < pattern.length) {
        if (pattern[p] == '{' && p + 2 < pattern.length && pattern[p + 2] == '}' && pattern[p + 1] in '0'..'9') {
            val idx = pattern[p + 1] - '0'
            if (idx < len) sb.append(tokens[i + idx].text)
            p += 2
        } else {
            sb.append(pattern[p])
        }
        p++
    }
    return sb.toString()
}

private fun MorphologicalAnalyser.buildOutputs(rule: RewriteRule, tokens: List<WordInfo>, i: Int): List<WordInfo> {
    val len = rule.match.size
    val matched = tokens.subList(i, i + len)
    val spanStart = matched[0].startOffset
    val spanEnd = matched[len - 1].endOffset

    val outputs = ArrayList<WordInfo>(rule.replace.size)
    for (t in rule.replace) {
        // Output k inherits flags from matched[k], clamped so a 1:N split inherits from its single source.
        val source = matched[minOf(outputs.size, len - 1)]
        val surface = if (t.text.isEmpty()) source.text else t.text
        val surfaceChanged = t.text.isNotEmpty()

        val w = source.copy().apply {
            text = surface
            // Unset template fields keep the source's value, so a 1:1 pin setting only DictForm keeps NormalizedForm.
            dictionaryForm = t.dictForm ?: (if (surfaceChanged) surface else source.dictionaryForm)
            normalizedForm = t.normalizedForm ?: (if (surfaceChanged) surface else source.normalizedForm)
            partOfSpeech = t.pos ?: source.partOfSpeech
            reading = t.reading ?: source.reading
            preMatchedWordId = t.pin
            preMatchedReadingIndex = if (t.pin != null) t.pinReadingIndex else null
            preMatchedCandidateWordIds = null
            preMatchedConjugations = null
            pinnedByRewriteRule = t.pin != null
            hardPinned = t.pin != null && t.hardPin
        }
        if (t.posSection != null) w.partOfSpeechSection1 = t.posSection
        if (t.recoverConjugations && t.pin != null)
            w.preMatchedConjugations = pinnedConjugationProcess(surface, w.dictionaryForm)

        outputs.add(w)
    }

    assignOffsets(outputs, spanStart, spanEnd)
    // C#'s Debug.Assert (surface text conserved at runtime) is debug-only there and left out here.
    return outputs
}

// Tiles the span by surface length; with unknown (-1) source offsets, interior boundaries stay -1.
private fun assignOffsets(outputs: List<WordInfo>, spanStart: Int, spanEnd: Int) {
    if (spanStart >= 0) {
        var cursor = spanStart
        for (w in outputs) {
            w.startOffset = cursor
            cursor += w.text.length
            w.endOffset = cursor
        }
    } else {
        for (w in outputs) {
            w.startOffset = -1
            w.endOffset = -1
        }
        outputs[0].startOffset = spanStart
    }
    outputs[outputs.size - 1].endOffset = spanEnd
}

// Test hook: runs a synthetic rule table (validated) instead of the static one.
internal fun MorphologicalAnalyser.applyRewriteRulesForTesting(
    input: MutableList<WordInfo>, rules: Array<RewriteRule>, phase: RewritePhase,
): MutableList<WordInfo> {
    val index = buildRewriteIndex(rules)[phase]
    return if (index == null || index.isEmpty) input else runRewriteRules(input, index)
}
