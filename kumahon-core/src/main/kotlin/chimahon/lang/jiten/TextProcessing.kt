// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4):
// Jiten.Parser/Helpers/MorphologicalAnalyser.TextProcessing.cs (+ ToFullWidthDigits from Jiten.Core/Utils/StringExtensions.cs)
package chimahon.lang.jiten

import java.nio.charset.Charset

// .NET -> Java regex: \p{IsHiragana}/\p{IsKatakana}/\p{IsCJKUnifiedIdeographs} are .NET *blocks*, written here as their
// ranges (Java's \p{IsX} means the script, which differs, and Android's ICU regex reads blocks differently); .NET's \s
// is Unicode whitespace, Java's is ASCII, so it is spelled out as WS.
private const val HIRA = "\\u3040-\\u309F"
private const val KATA = "\\u30A0-\\u30FF"
private const val CJK = "\\u4E00-\\u9FFF"
private const val WS = "\\s\\u0085\\p{Z}"
private const val NOT_EMPH_AFTER = "かきくけこがぎぐげござじずぜぞさしすせそたちつてとだぢづでどぱぴぷぺぽばびぶべぼカキクケコガギグゲゴザジズゼゾサシスセソタチツテトダヂヅデドパピプペポバビブベボ"

private val NonJapaneseCharRegex = Regex("""[^぀-ゟ゠-ヿ一-龯Ａ-Ｚａ-ｚ０-９々、-〃〈-】〔-〟！-／：-？［-＿｛-｠｢-･．\n…　―─()。！？「」）|]""")

// VN script ruby/emphasis markup: 癒#［１なお］す, も#［１・］ど (the digit is the base length).
private val HashRubyAnnotationRegex = Regex("""#［[0-9０-９]+[^］\n]*］""")
private val TildeAfterKanaRegex = Regex("""(?<=[$HIRA$KATA])[～〜]+""")
private val MultipleLongVowelRegex = Regex("""ー{2,}""")
private val EmphLongVowelKanjiHiraganaRegex = Regex("""(?<=[一-龯])ー(?=[぀-ゟ])""")
private val HayameWithoutWoRegex = Regex("""(?<!を)はやめ""")
private val HayaruWithoutGaRegex = Regex("""(?<!が)はやる""")
private val YatsureRegex = Regex("""(?<!あ)やつれ""")
private val DeNaiCompoundRegex = Regex("""(外|家)出(ない|なかった|なく)""")
private val EmphaticTsuRegex = Regex("""(?<=.[$HIRA$CJK])(?<!うわ)([っッ])(?![$NOT_EMPH_AFTER$CJK])""")
private val EllipsisBeforeEmphaticTsuRegex = Regex("""(?<=[$HIRA$CJK])…+(?=[っッ](?![$NOT_EMPH_AFTER$CJK]))""")
private val HontoKatakanaRegex = Regex("""ホント(バカ|ダメ|マジ|クソ|アホ)""")
private val ColloquialSshoRegex = Regex("""(?<!い)っしょ[ーう]?(?=[$WS\n]|$)""")
private val BanCompoundTsuRegex = Regex("""(?<=[一-龯])番っ""")
private val ColloquialYuuRegex = Regex("""(?<=(?:どー|どう|そー|そう|こー|こう|ああ|あー))ゆう""")

// A lone kana closed by punctuation after the pause is its own interjection (だが…ん？ must not become がん).
private val MidSentenceEllipsisRegex = Regex("""(?<=[$HIRA$KATA$CJK]{2})…+(?=[^\r\n…])(?![$HIRA$KATA][？！?!、。」』）\r\n])""")
private val EllipsisRunRegex = Regex("""…{2,}""")
private val LineEndEllipsisRegex = Regex("""…(?=\r?\n(?![ 　]*[」』）]))""")
private val LineEndParenRegex = Regex("""[）)](?=\r?\n)""")
private val KatakanaInterjectionTsuRegex = Regex("""([ァ-ヴ]ンッ)(?=[ァ-ヴぁ-ゔ$CJK])""")

// Not after など (本などして) or a stem-final mora fronting もどす (取りもどして); でも/かも never end in those.
private val ColloquialDoshiRegex = Regex("""(?<!な)(?<![りれびきちしい]も)どし(?=[たてよ])""")

// ーっ after hiragana is emphatic (けどーっ → けど), except before と where it is a mimetic adverb (ぼーっと).
private val EmphLongVowelSokuonRegex = Regex("""(?<=[぀-ゟ])ー+[っッ]+(?!と)""")

// Small vowel stretching a mimetic adverb (すぅっと → すっと); same-vowel only, since ファ/ティ are digraphs.
private val SmallVowelBeforeSokuonToRegex = Regex("""([ぁ-ゖァ-ヴ])([ぁぃぅぇぉァィゥェォ]+)(?=[っッ]と)""")

private const val VowelRowA = "あかがさざただなはばぱまやらわゃぁアカガサザタダナハバパマヤラワャァ"
private const val VowelRowI = "いきぎしじちぢにひびぴみりぃイキギシジチヂニヒビピミリィ"
private const val VowelRowU = "うくぐすずつづぬふぶぷむゆるゅぅゔウクグスズツヅヌフブプムユルュゥヴ"
private const val VowelRowE = "えけげせぜてでねへべぺめれぇエケゲセゼテデネヘベペメレェ"
private const val VowelRowO = "おこごそぞとどのほぼぽもよろをょぉオコゴソゾトドノホボポモヨロヲョォ"

/** 0-4 for the a/i/u/e/o row of a kana, -1 otherwise (also used by RepairStages). */
internal fun vowelRowOf(c: Char): Int = when {
    VowelRowA.indexOf(c) >= 0 -> 0
    VowelRowI.indexOf(c) >= 0 -> 1
    VowelRowU.indexOf(c) >= 0 -> 2
    VowelRowE.indexOf(c) >= 0 -> 3
    VowelRowO.indexOf(c) >= 0 -> 4
    else -> -1
}

private fun collapseSameVowelSmallBeforeSokuonTo(text: String): String =
    SmallVowelBeforeSokuonToRegex.replace(text) { m ->
        val row = vowelRowOf(m.groupValues[1][0])
        if (row >= 0 && m.groupValues[2].all { vowelRowOf(it) == row }) m.groupValues[1] else m.value
    }

// Shouted stretch before a clause-final ー run (行けぇーー → 行けー): drop the small vowel, keep the ー.
private val SmallVowelBeforeFinalLongVowelRegex = Regex("""(?<=[ぁ-ゖ])[ぁぃぅぇぉ](?=ー+([$WS\n！？!?]|$))""")

// ー stretching a shouted final い (せんぱーい); hiragana only (ボーイ), and not おーい, なーい or a repeat (わーいわーい).
private val LongVowelBeforeFinalIRegex = Regex("""(?<=[ぁ-ゖ][ぁ-ゖ])(?<!な)(?<!ー[ぁ-ゖ]{1,8})ー+(?=い([$WS\n！？!?」』）]|$))""")

// A small vowel after the opposite script (黙れェッ, ヤダぁ) is never a digraph; Sudachi shreds 黙れェ otherwise.
private val ScriptCrossingSmallVowelRegex = Regex("""(?<=[ぁ-ゖ])([ァィゥェォ]+[っッ]?)|(?<=[ァ-ヴ])([ぁぃぅぇぉ]+[っッ]?)""")

// A run of 2+ hiragana small vowels is elongation (急げぇぇ → 急|げぇぇ otherwise); ゃゅょ digraphs are untouched.
private val SameScriptSmallVowelRunRegex = Regex("""(?<=[ぁ-ゖ])[ぁぃぅぇぉ]{2,}""")

// A lone small vowel before clause-final っ is a shouted imperative (撃てぇっ!); すげぇ! and ねぇ stay.
private val ShoutedImperativeSmallVowelRegex = Regex("""(?<=[ぁ-ゖ])[ぁぃぅぇぉ]([っッ]+)(?=[$WS\n]|$)""")

// Stutter fragment (ぼ、ぼく); a preceding kana/kanji means a real particle or repeat (今は、はっきり, ええ、ええ).
private val StutterFragmentRegex = Regex("""(?<![ぁ-んァ-ヶー一-龯々])([ぁ-んァ-ヶ])[っッ]?[、,，][$WS]*(?=\1)""")

// 4+ identical kana are sound effects (ぼぼぼぼぼ); runs of 3 occur in real words (とっとと), left to MisparseGates.
private val StutteringRunRegex = Regex("""([ぁ-んァ-ヶ])([${WS}っッ]*\1){3,}""")

// 3+ identical digraph morae (じょじょじょ); small kana never start a mora, so each capture is one digraph.
private val StutteringDigraphRunRegex = Regex("""([ぁ-んァ-ヶ][ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ])([${WS}っッ]*\1){2,}""")

// Quotative って's gemination steals 〜通り's り (計画+通+りって).
private val TooriQuotativeRegex = Regex("""(?<=通り|どおり)(?=って)""")

// Sudachi shifts a hiragana long-vowel tail into a katakana word (じゃあアヒル → ああ + ヒル).
private val VowelTailKatakanaBoundaryRegex = Regex("""(?<=[ぁ-ゖ][あぁ])(?=[ァ-ヴ])""")

// Sudachi's spurious もしカ (2133220) steals a katakana name's first mora (もしカ|ティア); もしも/もしか stay.
private val MoshiKatakanaBoundaryRegex = Regex("""もし(?=[ァ-ヴ])""")

// Sudachi fuses を/へ + quotative って into a bogus verb (ことをって); no word contains をって/へって.
private val CaseParticleTteRegex = Regex("""(?<=[をへ])(?=って)""")

// っしょ after an i-adjective (すごいっしょ), which ColloquialSshoRegex's い guard blocks; と excluded for ずっと一緒.
private val IAdjSshoRegex = Regex("""(?<=[ぁ-ゖ]い)(?<!とい)っしょ[ーう]?(?=[$WS\n]|$)""")

// Sudachi shreds a demonstrative before って (こい|つっ|て, あ|そ|こっ|て).
private val DemonstrativePronounTteRegex = Regex("""([こそあど]いつ|ここ|そこ|あそこ|どこ)って""")

// 景気づけよ → 景気づけ (景気付け 2010780) + よ; NOT the volitional 景気づけよう.
private val KeikizukeYoRegex = Regex("""景気づけよ(?!う)""")

// Sudachi OOV-swallows らって after a pronoun's plural ら (キミ|らってやっぱり).
private val PronounRaTteRegex = Regex("""(?<=(?:キミ|きみ|君|僕|ぼく|俺|おれ|お前|おまえ|あいつ|こいつ|そいつ|あなた|彼|彼女|私|わたし|あたし|うち)ら)(?=って)""")

// Rough-speech elongated ある after a particle (覚えがあらァ, 金ならあらぁ → がある/ならある).
private val ElongatedAruRegex = Regex("""(が|なら)あら[ァぁ]""")

// Copula っす (2269410) after an i-adjective, else swallowed into a noun (いいっすか → イスカ); gated on what follows.
private val IAdjSsuRegex = Regex("""(?<=[ぁ-ゖ]い)(?<!とい)っす(?=[かよねぞなわぜさ、。！？…」]|けど|し|もん|から|が|[$WS\n]|$)""")

// Han characters outside Shift_JIS (爱, 们, 这): simplified Chinese, never Japanese text. C# uses code page 932.
private val NonShiftJisHan: BooleanArray by lazy {
    val charset = runCatching { Charset.forName("windows-31j") }.getOrElse { Charset.forName("Shift_JIS") }
    val enc = charset.newEncoder()
    BooleanArray(0x9FFF - 0x4E00 + 1) { !enc.canEncode((0x4E00 + it).toChar()) }
}

private fun isNonShiftJisHan(c: Char) = c in '一'..'鿿' && NonShiftJisHan[c - '一']

// Subtitle archives interleave Chinese tracks; kana-less lines with 2+ non-Shift_JIS hanzi are dropped.
private fun stripChineseLines(text: String): String {
    if (text.none(::isNonShiftJisHan)) return text
    val lines = text.split('\n').toMutableList()
    for (l in lines.indices) {
        var nonSjis = 0
        var hasKana = false
        for (c in lines[l]) {
            if (JapaneseTextHelper.isKana(c)) { hasKana = true; break }
            if (isNonShiftJisHan(c)) nonSjis++
        }
        if (!hasKana && nonSjis >= 2) lines[l] = if (lines[l].endsWith('\r')) "\r" else ""
    }
    return lines.joinToString("\n")
}

internal fun String.toFullWidthDigits(): String {
    val sb = StringBuilder(length)
    for (ch in this) sb.append(if (ch in '0'..'9') ch + ('０' - '0') else ch)
    return sb.toString()
}

/** C#'s PreprocessText(ref text, preserveStopToken, out rawContentCharCount): returns (text, rawContentCharCount). */
internal fun MorphologicalAnalyser.preprocessText(input: String, preserveStopToken: Boolean): Pair<String, Int> {
    val s = StopToken
    var text = stripChineseLines(input)
    text = HashRubyAnnotationRegex.replace(text, "")
    text = text.replace("<", " ").replace(">", " ").replace("〝", " ").replace("〟", " ")
    text = text.replace('‥', '…')
    text = text.toFullWidthDigits()
    text = NonJapaneseCharRegex.replace(text, "")

    val rawContentCharCount = countContentChars(text)

    if (!preserveStopToken) text = text.replace(s, "")

    text = LineEndParenRegex.replace(text) { it.value + LineEndParenMark }

    text = text
        .replace("「", "\n「 ")
        .replace("」", " 」\n")
        .replace("〈", " \n〈 ")
        .replace("〉", " 〉\n")
        .replace("\n（", " （")
        .replace("）", " ）\n")
        .replace("《", " \n《 ")
        .replace("》", " 》\n")
        .replace("“", " \n“ ")
        .replace("”", " ”\n")
        .replace("―", " ― ")
        .replace("。", "\n。\n")
        .replace("！", "\n！\n")
        .replace("？", "\n？\n")

    text = TildeAfterKanaRegex.replace(text, "ー")
    text = MultipleLongVowelRegex.replace(text, "ー")
    text = EmphLongVowelKanjiHiraganaRegex.replace(text, "")
    text = EmphLongVowelSokuonRegex.replace(text, "")
    text = collapseSameVowelSmallBeforeSokuonTo(text)
    text = SmallVowelBeforeFinalLongVowelRegex.replace(text, "")
    // Before the script-crossing split, which would strand あら as the interjection; clause-initial あらぁ stays.
    text = ElongatedAruRegex.replace(text, "\$1ある")
    text = ScriptCrossingSmallVowelRegex.replace(text, "$s\$1\$2")
    text = SameScriptSmallVowelRunRegex.replace(text, "")
    text = ShoutedImperativeSmallVowelRegex.replace(text, "\$1")
    // After the small-vowel deletions: おぉぉーーい must reduce to おーい, not おい.
    text = LongVowelBeforeFinalIRegex.replace(text, "")

    text = StutterFragmentRegex.replace(text, "")
    text = StutteringDigraphRunRegex.replace(text, "")
    text = StutteringRunRegex.replace(text, "")

    text = text
        .replace("垣間見", "垣間${s}見")
        .replace("今手", "今${s}手")
    text = HayameWithoutWoRegex.replace(text, "は${s}やめ")
    text = text.replace("もやる", "も${s}やる")
    text = HayaruWithoutGaRegex.replace(text, "は${s}やる")
    // Quotative って fragments やる into や+る; after the はやる split so 流行る is unaffected.
    text = text.replace("やるって", "やる${s}って")
    // Quotative って after なんとなく, else re-read as なんと + なくって.
    text = text.replace("なんとなくって", "なんとなく${s}って")
    text = text
        .replace("ええんや", "ええ${s}んや")
        .replace("べや", "べ${s}や")
        .replace("はいい", "は${s}いい")
        .replace("元国王", "元${s}国王")
        .replace("なんだろう", "なん${s}だろう")
        .replace("一人静かに", "一人${s}静かに")
        .replace("いやあんま", "いや${s}あんま")
        .replace("この手紙", "この${s}手紙")
        .replace("少女の手", "少女${s}の手")
        .replace("はたまたま", "は${s}たまたま")
        .replace("悶え苦しむ", "悶え${s}苦しむ")
        .replace("悶え苦しん", "悶え${s}苦しん")
        // すぐそこ (user_dic) must not eat the すぐ of もうすぐ
        .replace("もうすぐそこ", "もうすぐ${s}そこ")

    // Kana すみません collides with the verb 済む; before っ Sudachi's single でした fuses すいませんでした into 吸う.
    text = text
        .replace("すみませんでした", "すみません${s}でした") // すみ|ませんでした
        .replace("すいませんでした", "すいません${s}でした")
        .replace("この世界", "この${s}世界") // この世+界 → この|世界
        .replace("だけって", "だけ${s}って") // 広がっ+ただけ phantom けっ → だけ|って
        .replace("ははーん", "ははん") // は+はーん(ハーン khan) → ははん(2096970)
        .replace("にって", "に${s}って") // にっ+てこ blob → に + って + こと
        .replace("なんかいな", "なんか${s}いな") // なんか+い stolen → なんか + いない
        .replace("繋がりって", "繋がり${s}って") // 繋|が|り shredded by って → 繋がり + って
        .replace("んったら", "ん${s}ったら") // ちゃ|んっ|たら → ちゃん + ったら
        .replace("にいる", "に${s}いる") // にいる(name 5408860) → に + いる(居る)
        .replace("さっきこ", "さっき${s}こ") // sokuon-norm reads name さきこ(咲子)
        .replace("ないっていう", "ない${s}っていう") // って must not attach left into 〜ない expr
        // In hiragana text が行/は行 is always particle + 行〜 (母が行かせまい, が行方); row nouns keep katakana ガ行.
        .replace("が行", "が${s}行") // ガ行(1040670) fusion → が + 行〜
        .replace("は行", "は${s}行") // ハ行(1096940) fusion → は + 行〜
        // 金のこ (hacksaw) swallows 金のこと; gated on のこと so 金のこで切る keeps its entry.
        .replace("金のこと", "金${s}のこと")
        // 誰's だあれ must not eat なんだ's copula; keyed on なんだ/何だ so child-speech お姉さんだあれ？ keeps 誰.
        .replace("なんだあれ", "なんだ${s}あれ")
        .replace("何だあれ", "何だ${s}あれ")
        // Sudachi's てく contraction steals the く of くだせえ (勘弁して|く|だ|せえ).
        .replace("てくだせえ", "て${s}くだせえ")
        // Sudachi fuses るっす into a noun shard (わかるっす); る never ends a word before っす otherwise.
        .replace("るっす", "る${s}っす")
        // です steals the す of 済まして (顔ですましている); です+まし never occurs in prose.
        .replace("ですまし", "で${s}すまし")
        // After an i-adjective the いっ shard reads as 行ったら; the full tail keeps 会いに行ったら.
        .replace("いったらありゃしない", "い${s}ったらありゃしない")
        // Sudachi cuts ２つ|も|らって (ラッテ); 積もる's te-form is 積もって, so つもらっ has no other reading.
        .replace("つもらっ", "つ${s}もらっ")
        // Sudachi hands たい's い to 行って (知りた|いって); the cut also suits 重たいって and 鯛って.
        .replace("たいって", "たい${s}って")
        // Sudachi fuses るっていう into a dropped blob; る never ends a word before っていう otherwise.
        .replace("るっていう", "る${s}っていう")
    text = TooriQuotativeRegex.replace(text, s)
    text = VowelTailKatakanaBoundaryRegex.replace(text, s)
    text = MoshiKatakanaBoundaryRegex.replace(text, "もし$s")
    text = CaseParticleTteRegex.replace(text, s)
    text = DemonstrativePronounTteRegex.replace(text, "\$1${s}って")
    text = PronounRaTteRegex.replace(text, s)
    text = KeikizukeYoRegex.replace(text, "景気づけ${s}よ")

    text = text.replace('頚', '頸')

    text = text.replace("前出すぎ", "前${s}出すぎ")

    text = DeNaiCompoundRegex.replace(text, "\$1${s}出\$2")
    text = text.replace("届出さ", "届${s}出さ")
    // Sudachi's archaic 射出す(いだす) eats the noun 射出 before される/して
    text = text.replace("射出さ", "射出${s}さ")
    text = text.replace("射出し", "射出${s}し")
    // Anchors user_dic entries against connection costs: やつれ (not 操れ), 小木曽, するすると.
    text = YatsureRegex.replace(text, "${s}やつれ")
    // quotative と + かぶりを振る: SpecialCases とか otherwise steals the か (と|か|ぶり)
    text = text.replace("とかぶりを振", "と${s}かぶりを振")
    // Sudachi's 虫を殺す is the rare temper idiom; fiction means literal insect-killing.
    text = text.replace("虫を殺", "虫を${s}殺")
    text = text.replace("小木曽", "小木曽$s")
    text = text.replace("するすると", "${s}するすると")
    text = text.replace("ぶっち切", "ぶち切")
    // The playful おっはよー is おはよう; left alone the emphatic-っ boundary strands お (→ 尾).
    text = text.replace("おっはよ", "おはよ")
    text = EllipsisBeforeEmphaticTsuRegex.replace(text, "")
    text = EmphaticTsuRegex.replace(text, "$s\$1")
    // After EmphaticTsuRegex, else it cuts ぶ|っ and が+ぶ merges; ぶっ壊れる has no JMDict entry, so ぶっ (2698210) + 壊れた.
    text = text.replace("ぶっ壊れ", "${s}ぶっ${s}壊れ")
    text = BanCompoundTsuRegex.replace(text, "番${s}っ")

    text = text
        .replace("水魔法", "水${s}魔法")
        .replace("不適応", "不${s}適応")
        .replace("首落と", "首${s}落と")
        .replace("面の皮", "面${s}の皮")
        .replace("たっけ", "た${s}っけ")

    text = HontoKatakanaRegex.replace(text, "ホント$s\$1")
    text = KatakanaInterjectionTsuRegex.replace(text, "\$1$s")

    text = text
        .replace("バカバカ", "バカ${s}バカ")
        .replace("事大", "事${s}大")
        // Sudachi cuts 前大 (surname) + 戦
        .replace("前大戦", "前${s}大戦")
        .replace("人魚姫", "人魚${s}姫")
        .replace("日間", "日${s}間")
        .replace("何本", "何${s}本")
        .replace("年未公開", "年${s}未公開")
        .replace("足元気", "足元${s}気")

    text = text
        .replace("来イ", "来い")
        .replace("にちがいねえ", "にちがいない")
        .replace("せぇ", "さい")
        .replace("くせー", "くさい")
        .replace("ですぅ", "です")
        .replace("ごめんなさいっ", "ごめんなさい")

    text = text.replace("でもちょっと", "でも${s}ちょっと")
    text = text.replace("できんよう", "できん${s}よう")
    text = ColloquialSshoRegex.replace(text, "${s}っしょ")
    text = IAdjSshoRegex.replace(text, "${s}っしょ")
    text = IAdjSsuRegex.replace(text, "${s}っす")

    text = ColloquialDoshiRegex.replace(text, "どうし")
    text = ColloquialYuuRegex.replace(text, "いう")

    text = EllipsisRunRegex.replace(text, "…")
    text = MidSentenceEllipsisRegex.replace(text, "")
    text = LineEndEllipsisRegex.replace(text, "。")
    return text to rawContentCharCount
}

private fun countContentChars(text: String): Int = text.count { c ->
    c in '぀'..'ゟ' || // hiragana
        c in '゠'..'ヿ' || // katakana (incl. ー)
        c in '一'..'龯' || // CJK
        c == '々' ||
        c in 'Ａ'..'Ｚ' || // fullwidth A-Z
        c in 'ａ'..'ｚ' || // fullwidth a-z
        c in '０'..'９' // fullwidth 0-9
}

internal fun computeTokenOffsets(originalText: String, wordInfos: List<WordInfo>) {
    val text = originalText.replace("\r", "").replace("\n", "")
    var pos = 0
    for (word in wordInfos) {
        if (word.text.isEmpty() || word.partOfSpeech == PartOfSpeech.BlankSpace) continue
        val found = text.indexOf(word.text, pos)
        if (found >= 0) {
            word.startOffset = found
            word.endOffset = found + word.text.length
            pos = word.endOffset
        }
    }
}

/** Strips the ） marks; returns the text and their positions in the text with line breaks removed (C#'s ref text). */
internal fun takeLineEndParenMarks(text: String): Pair<String, Set<Int>> {
    val positions = HashSet<Int>()
    if (text.indexOf(LineEndParenMark) < 0) return text to positions
    val sb = StringBuilder(text.length)
    var flatPos = 0
    for (c in text) {
        if (c == LineEndParenMark) {
            positions.add(flatPos - 1)
            continue
        }
        sb.append(c)
        if (c != '\r' && c != '\n') flatPos++
    }
    return sb.toString() to positions
}

internal fun MorphologicalAnalyser.splitIntoSentences(
    originalText: String, wordInfos: List<WordInfo>, lineEndParens: Set<Int>,
): List<SentenceInfo> {
    val text = originalText.replace("\r", "").replace("\n", "")

    // Start positions give O(1) sentence lookup by offset.
    val sentenceData = ArrayList<Pair<SentenceInfo, Int>>()
    val sb = StringBuilder()
    var seenEnder = false
    var sentenceStartPos = 0

    for (i in text.indices) {
        val current = text[i]
        sb.append(current)

        if (current in SentenceEnders || i in lineEndParens) {
            seenEnder = true
            continue
        }

        if (seenEnder) {
            if (current in SentenceEnders) continue

            // The current character belongs to the next sentence.
            val sentenceText = sb.substring(0, sb.length - 1)
            sentenceData.add(SentenceInfo(sentenceText) to sentenceStartPos)

            sentenceStartPos = i
            sb.setLength(0)
            sb.append(current)
            seenEnder = false
        }
    }

    if (sb.isNotEmpty()) sentenceData.add(SentenceInfo(sb.toString()) to sentenceStartPos)

    if (sentenceData.isEmpty()) return emptyList()

    // Offsets come from raw Sudachi output and survive merge/split stages; IndexOf breaks when a stage edits Text.
    var sentenceIdx = 0

    for (word in wordInfos) {
        if (word.text.isEmpty() || word.partOfSpeech == PartOfSpeech.BlankSpace) continue
        if (word.startOffset < 0 || word.endOffset < 0) continue

        val wordPos = word.startOffset
        val wordEnd = word.endOffset

        while (sentenceIdx < sentenceData.size - 1) {
            val nextSentenceStart = sentenceData[sentenceIdx + 1].second
            if (wordPos < nextSentenceStart) break
            sentenceIdx++
        }

        val (sentence, sentenceStart) = sentenceData[sentenceIdx]
        var sentenceEnd = sentenceStart + sentence.text.length

        // A word spanning a boundary merges the sentences.
        while (wordEnd > sentenceEnd && sentenceIdx + 1 < sentenceData.size) {
            val nextSentence = sentenceData[sentenceIdx + 1].first
            sentence.text += nextSentence.text
            sentenceData.removeAt(sentenceIdx + 1)
            sentenceEnd = sentenceStart + sentence.text.length
        }

        sentence.words.add(SentenceWord(word, wordPos - sentenceStart, wordEnd - wordPos))
    }

    return sentenceData.map { it.first }
}
