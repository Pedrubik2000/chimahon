// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/MorphologicalAnalyser.cs
// (+ the input filter of Jiten.Parser/SudachiInterop.cs; Sudachi Java instead of Jiten's Rust FFI)
package chimahon.lang.jiten

import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.Dictionary
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.PathAnchor
import com.worksap.nlp.sudachi.Tokenizer
import com.worksap.nlp.sudachi.dictionary.Grammar
import java.nio.file.Path

/**
 * Jiten's MorphologicalAnalyser: Sudachi (mode B, Jiten's plugins and char.def from resources/jiten/) over the
 * preprocessed text, then the token stages ([runPipeline]), then sentences. The stages are written in other files as
 * extension functions on this class, `internal fun MorphologicalAnalyser.stageName(words: MutableList<WordInfo>):
 * MutableList<WordInfo>`, so they reach the internal members below like C#'s partial-class members.
 *
 * The callbacks are wired to [lexicon] as Parser.cs wires them; with no lexicon they stay null, which the stages treat
 * as "no dictionary" exactly as in C# (`HasCompoundLookup == null || ...`).
 *
 * [systemDictionary]/[userDictionary] replace Jiten's ParserRuntimeSettings paths (sudachi.json's systemDict/userDict).
 * Not thread-safe: one instance must not run concurrent parses (as in C#; Sudachi Java's Tokenizer isn't either).
 */
class MorphologicalAnalyser(
    val lexicon: JitenLexicon?,
    private val systemDictionary: Path? = null,
    private val userDictionary: Path? = null,
) {
    var hasCompoundLookup: ((String) -> Boolean)? = lexicon?.let { it::hasLookupForCompound }
    var hasNonNameCompoundLookup: ((String) -> Boolean)? = lexicon?.let { it::hasNonNameLookup }
    var hasPrioritizedNonNameCompoundLookup: ((String) -> Boolean)? = lexicon?.let { it::hasPrioritizedNonNameLookup }
    var hasKanaAppropriateCompoundLookup: ((String) -> Boolean)? = lexicon?.let { it::hasKanaAppropriateLookup }
    var hasSuruVerbCompoundLookup: ((String) -> Boolean)? = lexicon?.let { it::hasSuruVerbLookup }
    var getNonNameCompoundWordId: ((String) -> Int?)? = lexicon?.let { it::getNonNameCompoundId }
    var getNonNameCompoundFrequencyRank: ((String) -> Int?)? = lexicon?.let { it::getBestNonNameFrequencyRank }
    var hasVerbOrAdjectiveLookup: ((String) -> Boolean)? = lexicon?.let { it::hasVerbOrAdjectiveLookup }
    var hasExpressionLookup: ((String) -> Boolean)? = lexicon?.let { it::hasExpressionLookup }
    var hasCounterSenseLookup: ((String) -> Boolean)? = lexicon?.let { it::hasCounterSenseAvailable }

    /**
     * RetokeniseOovBlobs re-runs Sudachi on an OOV kana blob alone (Sudachi only blobs it because of the context).
     * Replaces C#'s captured _sudachiConfigPath/_sudachiDicPath/_sudachiMode/_sudachiUserDictCsv; set per parse.
     */
    internal var tokenizer: Tokenizer? = null
    internal var sudachiMode: Tokenizer.SplitMode = Tokenizer.SplitMode.B
    internal var retokeniseOovDisabled: Boolean = false

    /** Request-path parse in Jiten (takes a reserved Sudachi slot); kept for parity. */
    var interactive: Boolean = false

    /** C#'s _tokenStages (Pipeline.kt). */
    internal val tokenStages: List<TokenStage> by lazy { buildTokenStages() }

    /** Set for the duration of a pipeline run (RunPipeline creates it and clears it at the end). */
    internal var pipelineDeconjCache: HashMap<String, List<DeconjugationForm>>? = null

    internal fun pipelineCachedDeconjugate(hiragana: String): List<DeconjugationForm> {
        val cache = pipelineDeconjCache
        cache?.get(hiragana)?.let { return it }
        val forms = Deconjugator.instance.deconjugate(hiragana)
        cache?.putIfAbsent(hiragana, forms)
        return forms
    }

    /** Deconjugates part1+part2 with katakana folded to hiragana and ー after kana turned into that kana's vowel. */
    internal fun pipelineCachedDeconjugateConcat(part1: String, part2: String): List<DeconjugationForm> {
        val buf = CharArray(part1.length + part2.length)
        part1.toCharArray(buf, 0)
        part2.toCharArray(buf, part1.length)
        for (i in buf.indices) {
            val c = buf[i]
            if (c in 'ァ'..'ヶ') buf[i] = c - 0x60
        }
        if ('ー' in buf) {
            for (i in 1 until buf.size) {
                if (buf[i] != 'ー') continue
                val prev = buf[i - 1]
                if (prev.code in 0x3040 until 0x3140) buf[i] = KanaVowelMap[prev.code - 0x3040]
            }
        }
        val key = String(buf)
        pipelineDeconjCache!![key]?.let { return it }
        val forms = Deconjugator.instance.deconjugate(key)
        pipelineDeconjCache!!.putIfAbsent(key, forms)
        return forms
    }

    // Sudachi dictionaries: sudachi.json (with the user dictionary) and sudachi_nouserdic.json (morphemesOnly).
    private val dictionary: Dictionary by lazy { createDictionary(withUserDictionary = true) }
    private val noUserDictionary: Dictionary by lazy { createDictionary(withUserDictionary = false) }
    private val tokenizerB: Tokenizer by lazy { dictionary.create() }
    private val tokenizerA: Tokenizer by lazy { noUserDictionary.create() }

    private fun createDictionary(withUserDictionary: Boolean): Dictionary {
        val dic = requireNotNull(systemDictionary) { "MorphologicalAnalyser needs the Sudachi system dictionary path" }
        val config = Config.fromJsonString(SudachiJson, PathAnchor.classpath(MorphologicalAnalyser::class.java.classLoader))
            .systemDictionary(chimahon.lang.SudachiSystem.of(dic))
        if (withUserDictionary) userDictionary?.let { config.addUserDictionary(it) }
        return DictionaryFactory().create(config)
    }

    /** morphemesOnly uses Sudachi mode A; preserveStopToken keeps the stop token for the reader. */
    fun parse(text: String, morphemesOnly: Boolean = false, preserveStopToken: Boolean = false): List<SentenceInfo> =
        parseBatch(listOf(text), morphemesOnly, preserveStopToken).firstOrNull() ?: emptyList()

    fun parseWithCleanedOriginal(
        text: String, morphemesOnly: Boolean = false, preserveStopToken: Boolean = false,
    ): Pair<List<SentenceInfo>, String> {
        val cleanedOriginals = ArrayList<String>()
        val results = parseBatch(listOf(text), morphemesOnly, preserveStopToken, cleanedOriginals)
        return (results.firstOrNull() ?: emptyList()) to (cleanedOriginals.firstOrNull() ?: "")
    }

    // C#'s userDictCsv (a per-request user dictionary built by the Rust lib) has no Sudachi Java equivalent: left out.
    // Diagnostics and timings are left out too.
    fun parseBatch(
        texts: List<String>,
        morphemesOnly: Boolean = false,
        preserveStopToken: Boolean = false,
        cleanedOriginals: MutableList<String>? = null,
        rawContentCharCounts: MutableList<Int>? = null,
    ): List<List<SentenceInfo>> {
        if (texts.isEmpty()) return emptyList()

        val sudachiMaxBytes = 49_000
        val processedTexts = ArrayList<String>(texts.size)
        val originalTexts = ArrayList<String>(texts.size)
        val lineEndParens = ArrayList<Set<Int>>(texts.size)
        for (text in texts) {
            if ('\n' !in text && text.toByteArray(Charsets.UTF_8).size > sudachiMaxBytes) {
                processedTexts.add("")
                originalTexts.add("")
                lineEndParens.add(emptySet())
                cleanedOriginals?.add("")
                rawContentCharCounts?.add(0)
                continue
            }

            val (copy, rawCharCount) = preprocessText(text, preserveStopToken)
            rawContentCharCounts?.add(rawCharCount)

            var cleanedOriginal = copy.replace(" ", "")
            if (!preserveStopToken) cleanedOriginal = cleanedOriginal.replace(StopToken, "")
            val (stripped, parens) = takeLineEndParenMarks(cleanedOriginal)
            cleanedOriginal = stripped
            lineEndParens.add(parens)
            processedTexts.add(copy.replace(LineEndParenMark.toString(), ""))
            originalTexts.add(cleanedOriginal)
            cleanedOriginals?.add(cleanedOriginal)
        }

        val combinedText = if (texts.size == 1) processedTexts[0] else processedTexts.joinToString(" $BatchDelimiter ")

        val mode = if (morphemesOnly) Tokenizer.SplitMode.A else Tokenizer.SplitMode.B
        val tok = if (morphemesOnly) tokenizerA else tokenizerB

        // A late stage re-tokenises OOV kana blobs alone; Sudachi only blobs them because of surrounding context.
        tokenizer = tok
        sudachiMode = mode

        var allWordInfos: MutableList<WordInfo> = runSudachi(tok, mode, combinedText)

        val batches = ArrayList<MutableList<WordInfo>>()
        if (texts.size == 1) {
            batches.add(allWordInfos)
        } else {
            var currentBatch = ArrayList<WordInfo>()
            var j = 0
            while (j < allWordInfos.size) {
                val wi = allWordInfos[j]
                // Sudachi tokenizes ||| as three separate | tokens
                if (wi.text == StopToken && j + 2 < allWordInfos.size &&
                    allWordInfos[j + 1].text == StopToken && allWordInfos[j + 2].text == StopToken) {
                    batches.add(currentBatch)
                    currentBatch = ArrayList()
                    j += 2
                } else {
                    currentBatch.add(wi)
                }
                j++
            }
            batches.add(currentBatch)
        }
        allWordInfos = ArrayList()

        val results = ArrayList<List<SentenceInfo>>()
        var i = 0
        while (i < batches.size && i < originalTexts.size) {
            var wordInfos = batches[i]
            if (morphemesOnly) {
                results.add(listOf(SentenceInfo("").apply { wordInfos.mapTo(words) { SentenceWord(it, 0, 0) } }))
                i++
                continue
            }
            computeTokenOffsets(originalTexts[i], wordInfos)
            wordInfos = runPipeline(wordInfos)
            results.add(splitIntoSentences(originalTexts[i], wordInfos, lineEndParens[i]))
            i++
        }
        return results
    }

    /**
     * The Rust FFI call: Jiten's lib filters the input (FilterAllowedChars), returns nothing for text without Japanese,
     * and prints Sudachi's CLI output (per line, sentence-split, then EOS), which WordInfo parses. Offsets stay -1 as in
     * Jiten's Sudachi-line constructor; ComputeTokenOffsets sets them. Boundary margins are diagnostics-only: not set.
     */
    private fun runSudachi(tok: Tokenizer, mode: Tokenizer.SplitMode, input: String): MutableList<WordInfo> {
        val text = filterAllowedChars(input)
        val words = ArrayList<WordInfo>()
        if (hasNoJapaneseChars(text)) return words
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            var bars = trailingKanaProlongedMarks(line)
            var stripped = line
            if (bars.isNotEmpty()) {
                stripped = line.filterIndexed { i, _ -> i !in bars }
                // The fork adds the ー-less words to the lattice beside the normal paths: keep whichever path costs
                // less (mode C costs; mode B splits drop them), so dictionary words with ー (やべー) still win.
                if (pathCost(tok, line) < pathCost(tok, stripped)) { bars = emptyList(); stripped = line }
            }
            // Each dropped ー goes back on the token that ends where it stood (positions in the stripped line).
            val ends = bars.mapIndexed { k, p -> p - k }.toHashSet()
            var pos = 0
            for (sentence in tok.tokenizeSentences(mode, stripped))
                for (m in sentence) {
                    val w = WordInfo.fromSudachi(m, -1, -1)
                    pos += w.text.length
                    if (pos in ends) w.text += "ー"
                    words.add(w)
                }
        }
        return words
    }

    /** Best-path cost (to EOS) of each sentence, summed. Sudachi Java hides it: read by reflection (lang keeps Sudachi). */
    private fun pathCost(tok: Tokenizer, line: String): Int {
        val grammar = TokenizerGrammar.get(tok) as Grammar
        var total = 0
        for (sentence in tok.tokenizeSentences(Tokenizer.SplitMode.C, line)) {
            val last = (MorphemePath.get(sentence) as List<*>).lastOrNull() ?: continue
            total += NodeTotalCost.getInt(last) + grammar.getConnectCost(NodeRightId.getShort(last), grammar.eosParameter[0])
        }
        return total
    }

    internal companion object {
        private fun field(cls: String, name: String) = Class.forName("com.worksap.nlp.sudachi.$cls").getDeclaredField(name).apply { isAccessible = true }
        private val TokenizerGrammar by lazy { field("JapaneseTokenizer", "grammar") }
        private val MorphemePath by lazy { field("MorphemeList", "path") }
        private val NodeTotalCost by lazy { field("LatticeNodeImpl", "totalCost") }
        private val NodeRightId by lazy { field("LatticeNodeImpl", "rightId") }

        /** Hiragana (offset 0x3040) -> the vowel a following ー stands for; '\u0000' for anything else. */
        val KanaVowelMap: CharArray = CharArray(0x100).apply {
            for (c in "おこそとのほもよろをごぞどぼぽょ") this[c.code - 0x3040] = 'う'
            for (c in "うくすつぬふむゆるぐずづぶぷゅ") this[c.code - 0x3040] = 'う'
            for (c in "えけせてねへめれげぜでべぺ") this[c.code - 0x3040] = 'え'
            for (c in "いきしちにひみりぎじぢびぴ") this[c.code - 0x3040] = 'い'
            for (c in "あかさたなはまやらわがざだばぱゃ") this[c.code - 0x3040] = 'あ'
        }

        /** Jiten's Shared/resources/sudachi.json with Sudachi Java's class names; files from resources/jiten/. */
        private val SudachiJson = """
            {
              "characterDefinitionFile": "jiten/char.def",
              "inputTextPlugin": [
                { "class": "com.worksap.nlp.sudachi.DefaultInputTextPlugin", "rewriteDef": "jiten/rewrite.def" },
                { "class": "com.worksap.nlp.sudachi.ProlongedSoundMarkInputTextPlugin",
                  "prolongedSoundMarks": ["ー", "-", "⁓", "〜", "〰"], "replacementSymbol": "ー" },
                { "class": "com.worksap.nlp.sudachi.IgnoreYomiganaPlugin",
                  "leftBrackets": ["(", "（"], "rightBrackets": [")", "）"], "maxYomiganaLength": 4 }
              ],
              "oovProviderPlugin": [
                { "class": "com.worksap.nlp.sudachi.MeCabOovProviderPlugin", "charDef": "jiten/char.def", "unkDef": "jiten/unk.def" },
                { "class": "com.worksap.nlp.sudachi.SimpleOovProviderPlugin",
                  "oovPOS": ["補助記号", "一般", "*", "*", "*", "*"], "leftId": 5968, "rightId": 5968, "cost": 3857 }
              ],
              "pathRewritePlugin": [
                { "class": "com.worksap.nlp.sudachi.JoinNumericPlugin", "enableNormalize": true },
                { "class": "com.worksap.nlp.sudachi.JoinKatakanaOovPlugin",
                  "oovPOS": ["名詞", "普通名詞", "一般", "*", "*", "*"], "minLength": 3 }
              ]
            }
        """.trimIndent()

        private val AllowedChars: BooleanArray = BooleanArray(65536).apply {
            fun range(from: Int, to: Int) { for (c in from..to) this[c] = true }
            range(0x3040, 0x309F) // Hiragana
            range(0x30A0, 0x30FF) // Katakana
            range(0x4E00, 0x9FAF) // CJK Unified Ideographs
            range(0xFF21, 0xFF3A) // Fullwidth A-Z
            range(0xFF41, 0xFF5A) // Fullwidth a-z
            range(0xFF10, 0xFF19) // Fullwidth 0-9
            this[0x3005] = true // 々
            range(0x3001, 0x3003) // 、。〃
            range(0x3008, 0x3011) // 〈〉《》「」『』【】
            range(0x3014, 0x301F) // 〔〕〖〗〘〙〚〛〜〝〞〟
            range(0xFF01, 0xFF0F) // ！＂＃＄％＆＇（）＊＋，－．／
            range(0xFF1A, 0xFF1F) // ：；＜＝＞？
            range(0xFF3B, 0xFF3F) // ［＼］＾＿
            range(0xFF5B, 0xFF60) // ｛｜｝～｟｠
            range(0xFF62, 0xFF65) // Halfwidth ｢｣､･
            for (c in "|\n…　―─() ") this[c.code] = true // stop token, newline, …, 　, ―, ─, ( ), space
        }

        private fun hasNoJapaneseChars(text: String): Boolean = text.none { c ->
            c.code in 0x3040..0x309F || c.code in 0x30A0..0x30FF || c.code in 0x4E00..0x9FAF ||
                c.code in 0xFF21..0xFF3A || c.code in 0xFF41..0xFF5A
        }

        /**
         * Jiten's private sudachi.rs fork has a TrailingKanaProlongedPlugin (its sudachi_lib exports it; the source isn't
         * public). Jiten.Tests: "Trailing ー after hiragana/kanji: lattice-level lookup finds the word without ー,
         * original surface preserved" (わかるー, すごいー, 休憩ー come out whole). Here a ー after hiragana or kanji
         * that ends a word (line end, space or punctuation next: そっかー、) is left out of Sudachi's input and put back
         * on the token before it. (An input text plugin can't drop it in Sudachi Java: tokenizeSentences then reads the
         * dropped ー as a sentence of its own.) Returns those ー's indices.
         */
        internal fun trailingKanaProlongedMarks(line: String): List<Int> = (1 until line.length).filter { i ->
            val prev = line[i - 1]
            val next = line.getOrNull(i + 1)
            line[i] == 'ー' && (prev in 'ぁ'..'ゖ' || prev in '一'..'龯' || prev == '々') && (next == null || !isWordChar(next))
        }

        /** Kana (incl. ー), kanji, 々 and full-width letters or digits: what a ー can stretch into. */
        private fun isWordChar(c: Char) = c in '぀'..'ヿ' || c in '一'..'龯' || c == '々' ||
            c in 'Ａ'..'Ｚ' || c in 'ａ'..'ｚ' || c in '０'..'９'

        /** Returns the same instance when nothing is removed. */
        internal fun filterAllowedChars(input: String): String =
            if (input.all { AllowedChars[it.code] }) input else input.filter { AllowedChars[it.code] }
    }
}
