// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.Disambiguation.cs
package chimahon.lang.jiten

import chimahon.lang.jiten.PartOfSpeech as P

private fun WordInfo.isNounOrCommon() = partOfSpeech == P.Noun || partOfSpeech == P.CommonNoun

internal fun MorphologicalAnalyser.applyContextPins(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    var i = wordInfos.size - 1
    while (i >= 0) {
        val word = wordInfos[i]
        if (word.text == "なん" || word.text == "フン" || word.text == "ふん")
            word.partOfSpeech = P.Prefix

        if (word.text == "そう")
            word.partOfSpeech = P.Adverb

        // Kana ツバ is 唾, or 鍔 near 刀の/帽子; both pinned, as the context-blind word cache reuses whichever was cached first.
        if ((word.text == "ツバ" || word.text == "つば") && word.isNounOrCommon()) {
            val swordContext = i >= 2 && wordInfos[i - 1].text == "の" &&
                wordInfos[i - 2].text in setOf("刀", "剣", "太刀", "刃")
            var hatContext = false
            var k = maxOf(0, i - 2)
            while (k < minOf(wordInfos.size, i + 5) && !hatContext) {
                hatContext = wordInfos[k].text.contains("帽子")
                k++
            }
            word.preMatchedWordId = if (swordContext || hatContext) 1433790 else 1408410
        }

        // Sudachi tags the noun 張り "tension" a suffix too (張りのある, 声に張りがない); a true suffix needs a noun host.
        if (word.text == "張り" && word.partOfSpeech in setOf(P.Suffix, P.Noun, P.CommonNoun) && word.preMatchedWordId == null &&
            (i == 0 || (wordInfos[i - 1].partOfSpeech !in setOf(P.Noun, P.CommonNoun, P.Name, P.Pronoun, P.Suffix) &&
                wordInfos[i - 1].text !in setOf("」", "』", "）", ")")))) {
            word.partOfSpeech = P.Noun
            word.preMatchedWordId = 1427760
        }

        // Verb 連用形 + 方 is かた "way of doing", never ほう.
        val stemVerb = if (word.text.length >= 3 && word.text.last() == '方' && word.preMatchedWordId == null &&
            word.isNounOrCommon() && hasNonNameCompoundLookup?.invoke(word.text) != true)
            tryGetRenyoukeiVerb(word.text.dropLast(1)) else null
        if (stemVerb != null) {
            val stemText = word.text.dropLast(1)
            val cut = if (word.startOffset >= 0) word.startOffset + stemText.length else -1
            wordInfos.add(i + 1, word.copy().apply {
                text = "方"; dictionaryForm = "方"; normalizedForm = "方"; reading = "カタ"
                partOfSpeech = P.Noun; startOffset = cut
                preMatchedWordId = 1516925; preMatchedReadingIndex = 0
            })
            word.text = stemText
            word.dictionaryForm = stemVerb
            word.normalizedForm = stemVerb
            val r = word.reading
            word.reading = if (r.length > 2 && r.endsWith("カタ")) r.dropLast(2) else ""
            word.partOfSpeech = P.Verb
            word.partOfSpeechSection1 = PartOfSpeechSection.None
            word.endOffset = cut
        } else if (word.text == "方" && word.preMatchedWordId == null && i > 0 &&
            wordInfos[i - 1].partOfSpeech == P.Verb && tryGetRenyoukeiVerb(wordInfos[i - 1].text) != null) {
            word.preMatchedWordId = 1516925
            word.preMatchedReadingIndex = 0
        }

        // The user-dic やしない makes Sudachi misread the ichidan stem before it (バレ[地名]); it only follows a 連用形.
        if (i + 1 < wordInfos.size && wordInfos[i + 1].text == "やしない" &&
            word.partOfSpeech != P.Verb && word.preMatchedWordId == null &&
            word.text.length >= 2 && hasVerbOrAdjectiveLookup?.invoke(normalizeToHiragana(word.text) + "る") == true) {
            word.partOfSpeech = P.Verb
            clearPosSections(word)
            word.dictionaryForm = word.text + "る"
            word.normalizedForm = word.text + "る"
        }

        // Kana うら in a noun slot (舞台のうらで) is 裏; the archaic pronoun only heads a clause (うらは).
        if (word.text == "うら" && word.partOfSpeech == P.Pronoun && word.preMatchedWordId == null &&
            ((i > 0 && wordInfos[i - 1].text == "の") ||
                (i + 1 < wordInfos.size && wordInfos[i + 1].text in setOf("に", "で", "から", "へ", "の", "を", "側")))) {
            word.partOfSpeech = P.Noun
            word.preMatchedWordId = 1550190
            word.preMatchedReadingIndex = 1
        }

        // "Ouch" あいた stands alone; before a noun or まま it is 開く's past (あいたままの扉).
        if (word.text == "あいた" && word.partOfSpeech == P.Interjection && word.preMatchedWordId == null &&
            i + 1 < wordInfos.size &&
            wordInfos[i + 1].partOfSpeech in setOf(P.Noun, P.CommonNoun, P.Adverb) &&
            wordInfos[i + 1].text.let { nextText ->
                nextText.isNotEmpty() && (JapaneseTextHelper.isKanji(nextText[0]) ||
                    (JapaneseTextHelper.isKana(nextText[0]) && nextText[0] != 'っ' && nextText[0] != 'ッ'))
            }) {
            word.partOfSpeech = P.Verb
            word.dictionaryForm = "あく"
            word.normalizedForm = "開く"
            word.preMatchedWordId = 1586270
            word.preMatchedReadingIndex = 3
            word.preMatchedConjugations = pinnedConjugationProcess(word.text, "あく")
        }

        // Kana さらう defaults to 攫う (さらわれた, 話題をさらう); 浚う needs a dredging object, 復習う a memory/lines one.
        if (word.dictionaryForm == "さらう" && word.preMatchedWordId == null &&
            (word.partOfSpeech == P.Verb || word.partOfSpeech == P.Unknown) && word.text.startsWith("さら")) {
            var contextWord: String? = null
            var k = maxOf(0, i - 3)
            while (k < i && contextWord == null) {
                if (wordInfos[k].text in SarauDredgeContext || wordInfos[k].text in SarauReviewContext)
                    contextWord = wordInfos[k].text
                k++
            }
            when {
                contextWord == null -> { word.preMatchedWordId = 1593870; word.preMatchedReadingIndex = 3 }
                contextWord in SarauReviewContext -> { word.preMatchedWordId = 1500810; word.preMatchedReadingIndex = 2 }
                else -> { word.preMatchedWordId = 1593865; word.preMatchedReadingIndex = 2 }
            }
            word.preMatchedConjugations = pinnedConjugationProcess(word.text, "さらう")
        }

        // Sudachi's tag settles bare や: 形状詞 is kana 嫌 (やだ), 助動詞 the Kansai copula (んやけど), never 矢.
        if (word.text == "や" && word.preMatchedWordId == null) {
            if (word.partOfSpeech == P.NaAdjective) {
                word.preMatchedWordId = 1587610
                word.preMatchedReadingIndex = 3
            } else if (word.partOfSpeech == P.Auxiliary) {
                word.preMatchedWordId = 2028960
            }
        }

        // Kana し/した/して lemmatised as する is that verb, not 詩/舌; the conjunction して keeps its own lemma.
        if (word.text in setOf("し", "した", "して") && (word.partOfSpeech == P.Verb || word.partOfSpeech == P.Unknown) &&
            word.dictionaryForm in setOf("する", "為る", "した") && word.preMatchedWordId == null) {
            word.preMatchedWordId = 1157170
            word.preMatchedConjugations = pinnedConjugationProcess(word.text, "する")
        }

        // Clause-initial ようは is 要は "in short", not 様+は.
        if (word.text == "よう" && word.isNounOrCommon() &&
            i + 1 < wordInfos.size && wordInfos[i + 1].text == "は" && wordInfos[i + 1].partOfSpeech == P.Particle &&
            (i == 0 || wordInfos[i - 1].partOfSpeech == P.SupplementarySymbol)) {
            absorbNext(wordInfos, i, "ヨウハ")
            word.dictionaryForm = "ようは"
            word.normalizedForm = "ようは"
            word.partOfSpeech = P.Conjunction
            word.preMatchedWordId = 1914670
        }

        // きった/きって after a 連用形 is the completion auxiliary 切る (落ちきった); Sudachi lemmatises it as 来る.
        if ((word.text == "きった" || word.text == "きって") && word.partOfSpeech == P.Verb &&
            word.preMatchedWordId == null &&
            i > 0 && wordInfos[i - 1].partOfSpeech == P.Verb &&
            !wordInfos[i - 1].text.endsWith('て') && !wordInfos[i - 1].text.endsWith('で'))
            pinVerb(word, 1384830, "切る", word.text, "きる")

        // くれ (or shouted くれえ) after を/て/で is くれる's imperative (許してくれえ), not 暮れ or くらい.
        if (word.text in setOf("くれ", "くれえ", "くれぇ", "くれー") && i > 0 &&
            (wordInfos[i - 1].text in setOf("を", "て", "で") ||
                wordInfos[i - 1].text.endsWith("て") || wordInfos[i - 1].text.endsWith("で")) &&
            word.preMatchedWordId == null)
            pinVerb(word, 1269130, "くれる", "くれ")

        // Utterance-initial ねえ before a pause or name is "hey" (ねえ、悟); the negation ねえ follows content (金なんてねえ).
        if (word.text == "ねえ" && word.partOfSpeech == P.Interjection &&
            atClauseStart(wordInfos, i) &&
            (followedByPunctuationOrEnd(wordInfos, i) ||
                wordInfos[i + 1].partOfSpeech == P.Name || wordInfos[i + 1].partOfSpeech == P.Pronoun ||
                PosMapper.isNameLikeSudachiNoun(wordInfos[i + 1].partOfSpeech,
                    wordInfos[i + 1].partOfSpeechSection1, wordInfos[i + 1].partOfSpeechSection2,
                    wordInfos[i + 1].partOfSpeechSection3)) &&
            word.preMatchedWordId == null) {
            word.dictionaryForm = "ねえ"
            word.normalizedForm = "ねえ"
            word.preMatchedWordId = 2029080
        }

        // Kana いった after a place/direction word is 行く "went" (どこいった), not 言う.
        if (word.text == "いった" && i > 0 &&
            wordInfos[i - 1].text in setOf("どこ", "どっか", "こっち", "あっち", "そっち", "どこか"))
            pinVerb(word, 1578850, "行く", "いった", "いく")

        // 出られな|いって is ない + って: Sudachi lets いく steal ない's final mora.
        if (word.text == "いって" && (word.dictionaryForm == "いく" || word.dictionaryForm == "行く") &&
            i > 0 && wordInfos[i - 1].text == "な" && wordInfos[i - 1].partOfSpeech == P.Auxiliary) {
            val naAux = wordInfos[i - 1]
            naAux.text = "ない"
            naAux.dictionaryForm = "ない"
            naAux.normalizedForm = "ない"
            naAux.reading = "ナイ"
            word.text = "って"
            word.dictionaryForm = "って"
            word.normalizedForm = "って"
            word.reading = "ッテ"
            word.partOfSpeech = P.Particle
            word.preMatchedWordId = null
        }

        // A single kanji clipped in an exclamation (痛ぇぇ！, 暗っ！) is the i-adjective, not the noun/suffix homograph.
        if (word.text.length == 1 && word.text[0] in '一'..'鿿' &&
            word.partOfSpeech in setOf(P.Noun, P.CommonNoun, P.Suffix) &&
            (i == 0 || wordInfos[i - 1].partOfSpeech in setOf(P.SupplementarySymbol, P.Symbol, P.Interjection, P.BlankSpace))) {
            val clipNext = nextNonSplitMarker(wordInfos, i)
            if (clipNext < wordInfos.size &&
                (wordInfos[clipNext].partOfSpeech == P.SupplementarySymbol || wordInfos[clipNext].partOfSpeech == P.Symbol) &&
                wordInfos[clipNext].text.isNotEmpty() && wordInfos[clipNext].text[0] in "ぇェっッー") {
                // Exclamations clip only common adjectives: 今っ！ is the noun 今, not the rare 今い.
                val clippedAdjId = getNonNameCompoundWordId?.invoke(word.text + "い")
                val clippedAdjRank = if (clippedAdjId != null) getNonNameCompoundFrequencyRank?.invoke(word.text + "い") else null
                if (clippedAdjId != null && clippedAdjRank != null && clippedAdjRank <= 5000) {
                    word.partOfSpeech = P.IAdjective
                    word.dictionaryForm = word.text + "い"
                    word.normalizedForm = word.text + "い"
                    word.preMatchedWordId = clippedAdjId
                }
            }
        }

        // 来 before やがる is the verb stem (来やがる stays split by convention), not the suffix らい.
        if (word.text == "来" && word.partOfSpeech == P.Verb && i + 1 < wordInfos.size &&
            wordInfos[i + 1].dictionaryForm == "やがる")
            word.preMatchedWordId = 1547720

        // Clause-final 行け is 行く's imperative (先に行け！), not the potential 行ける.
        if ((word.text == "行け" || word.text == "行けー") && followedByPunctuationOrEnd(wordInfos, i))
            pinVerb(word, 1578850, "行く", "行け")

        // Suffix 宛 is 宛て "addressed to".
        if (word.text == "宛" && word.partOfSpeech == P.Suffix && i > 0 && word.preMatchedWordId == null)
            word.preMatchedWordId = 1448820

        // ばっか right after a te-form or noun is ばかり (仕事ばっか), not 馬鹿 (あんたはばっか); pinned as 麦価/幕下 share the kana.
        if (word.text == "ばっか" && word.preMatchedWordId == null && i > 0 &&
            wordInfos[i - 1].let { prev ->
                (prev.partOfSpeech == P.Verb && (prev.text.endsWith('て') || prev.text.endsWith('で'))) ||
                    (prev.partOfSpeech == P.Particle && (prev.text == "て" || prev.text == "で")) ||
                    prev.partOfSpeech in setOf(P.Noun, P.CommonNoun, P.Pronoun, P.Name)
            }) {
            word.preMatchedWordId = 2857403
            word.dictionaryForm = "ばっか"
            word.normalizedForm = "ばっか"
            word.partOfSpeech = P.Particle
        }

        // Single kanji listed with another (仁も義も智も) are concepts Sudachi name-tags (智→サトシ); a name partner (太郎と智) is not.
        if (word.partOfSpeech == P.Noun && word.text.length == 1 &&
            JapaneseTextHelper.isKanji(word.text[0]) && word.preMatchedWordId == null &&
            ((i >= 2 && isListParticle(wordInfos[i - 1]) && isSingleKanjiNoun(wordInfos[i - 2])) ||
                (i + 2 < wordInfos.size && isListParticle(wordInfos[i + 1]) && isSingleKanjiNoun(wordInfos[i + 2]))))
            clearPosSections(word)

        // A trailing kanji that is no word alone is a stolen compound tail (敵陣|営 → 敵|陣営); a standalone one stays (東京|都).
        val nonNameLookup = hasNonNameCompoundLookup
        if (word.isNounOrCommon() && word.text.length == 2 && word.preMatchedWordId == null &&
            JapaneseTextHelper.isKanji(word.text[0]) && JapaneseTextHelper.isKanji(word.text[1]) &&
            i + 1 < wordInfos.size && nonNameLookup != null) {
            val tail = wordInfos[i + 1]
            if (tail.isNounOrCommon() && tail.text.length == 1 && JapaneseTextHelper.isKanji(tail.text[0]) &&
                tail.preMatchedWordId == null &&
                !nonNameLookup(tail.text) &&
                nonNameLookup(word.text.substring(0, 1)) &&
                nonNameLookup(word.text.substring(1) + tail.text)) {
                val newTail = word.text.substring(1) + tail.text
                tail.text = newTail
                tail.dictionaryForm = newTail
                tail.normalizedForm = newTail
                // The stolen cut's readings are wrong on both sides; empty lets JMDict matching supply them.
                tail.reading = ""
                tail.partOfSpeech = P.Noun
                word.text = word.text.substring(0, 1)
                word.dictionaryForm = word.text
                word.normalizedForm = word.text
                word.reading = ""
                if (word.endOffset >= 0) {
                    tail.startOffset = word.endOffset - 1
                    word.endOffset -= 1
                }
            }
        }

        // 方々 + を/に + movement verb is ほうぼう, after の/adnominal かたがた (その方々); Sudachi's reading is unreliable.
        if (word.text == "方々") {
            val movementFollows = i + 2 < wordInfos.size && (wordInfos[i + 1].text == "を" || wordInfos[i + 1].text == "に") &&
                (wordInfos[i + 2].dictionaryForm in setOf("歩く", "巡る", "旅する", "走る", "駆ける", "散る", "散らばる", "逃げる", "飛ぶ") ||
                    wordInfos[i + 2].dictionaryForm.endsWith("回る") ||
                    wordInfos[i + 2].dictionaryForm.endsWith("散る"))
            if (movementFollows)
                word.preMatchedWordId = 1584105
            else if (i > 0 && (wordInfos[i - 1].text == "の" || wordInfos[i - 1].partOfSpeech == P.PrenounAdjectival))
                word.preMatchedWordId = 1584100
        }

        // Clause-final だい (何がだい) is the question particle, not 代 (Sudachi tags it Prefix).
        if (word.text == "だい" && word.partOfSpeech == P.Prefix && followedByPunctuationOrEnd(wordInfos, i)) {
            word.partOfSpeech = P.Particle
            word.dictionaryForm = "だい"
            word.preMatchedWordId = 2097680
        }

        // Kana reciprocal 〜あう (憎みあって) can't reach its 合う entry and drops; surfaces that resolve (つきあって) skip the pin.
        if (word.partOfSpeech == P.Verb && !word.text.contains('合') &&
            word.normalizedForm.isNotEmpty() && word.normalizedForm.endsWith("合う") && word.normalizedForm.length >= 3 &&
            !kanaSurfaceResolvesViaLookup(word)) {
            val reciprocalAuId = getNonNameCompoundWordId?.invoke(word.normalizedForm)
            if (reciprocalAuId != null) {
                word.preMatchedWordId = reciprocalAuId
                word.preMatchedConjugations = pinnedConjugationProcess(word.text, word.dictionaryForm)
            }
        }

        // あって after a verb 連用形 is reciprocal 合う (読みあって); ある follows a noun or idiom frame (実りあって, 命あっての).
        // Runs after the って re-cut; pinning earlier triggers the っ+て mora theft.
        if (word.partOfSpeech == P.Verb && (word.normalizedForm == "有る" || word.normalizedForm == "在る") &&
            word.text.startsWith("あっ") &&
            !(i + 1 < wordInfos.size && wordInfos[i + 1].text in setOf("の", "も", "こそ")) &&
            i > 0 && wordInfos[i - 1].partOfSpeech == P.Verb &&
            renyokeiSurfaceToVerb(wordInfos[i - 1].text) != null) {
            word.dictionaryForm = "あう"
            word.normalizedForm = "合う"
            word.preMatchedWordId = 1284430
            word.preMatchedConjugations = pinnedConjugationProcess(word.text, word.dictionaryForm)
        }

        // CombineInflections glues quotative って onto i-adjectives (硬いって); only 〜くって (嬉しくって) is a real te-form.
        if (word.partOfSpeech == P.IAdjective && word.text.length >= 4 &&
            word.text.endsWith("って") && word.text[word.text.length - 3] != 'く' &&
            hasNonNameCompoundLookup?.invoke(word.text.dropLast(2)) == true) {
            val mid = if (word.endOffset >= 0) word.endOffset - 2 else -1
            val tte = word.copy().apply {
                text = "って"
                dictionaryForm = "って"
                normalizedForm = "って"
                reading = "ッテ"
                partOfSpeech = P.Particle
                preMatchedWordId = 2086960
                startOffset = mid
                endOffset = word.endOffset
            }
            word.text = word.text.dropLast(2)
            word.endOffset = mid
            if (word.reading.endsWith("ッテ"))
                word.reading = word.reading.dropLast(2)
            wordInfos.add(i + 1, tte)
        }

        // もんか after a predicate is rhetorical "as if" (あるもんか), not 門下 (剣の門下); ものか stays ambiguous (どうしたものか).
        val prevIsPredicate = i > 0 &&
            wordInfos[i - 1].partOfSpeech in setOf(P.Verb, P.IAdjective, P.Auxiliary)
        if (prevIsPredicate && word.text == "もん" && i + 1 < wordInfos.size && wordInfos[i + 1].text == "か")
            absorbNext(wordInfos, i, "モンカ")
        if (prevIsPredicate && word.text == "もんか") {
            word.partOfSpeech = P.Particle
            word.dictionaryForm = "ものか"
            word.normalizedForm = "ものか"
            word.preMatchedWordId = 2130440
        }

        if (word.text == "おい")
            word.partOfSpeech = P.Interjection

        applyCounterPins(wordInfos, i)

        // 家 before a case particle is the noun いえ, not the suffix け.
        if (word.text == "家" && word.partOfSpeech == P.Suffix &&
            i + 1 < wordInfos.size && wordInfos[i + 1].partOfSpeech == P.Particle &&
            wordInfos[i + 1].text in setOf("から", "を", "が", "に", "で", "へ", "の", "は", "も"))
            word.partOfSpeech = P.Noun

        if (word.text == "山" && word.partOfSpeech == P.Suffix)
            word.partOfSpeech = P.Noun

        if ((word.text == "だろう" || word.text == "だろ") && word.partOfSpeech == P.Auxiliary) {
            word.partOfSpeech = P.Expression
            word.dictionaryForm = word.text
        }

        if (word.text == "だあ") {
            word.text = "だ"
            word.dictionaryForm = "です"
            word.reading = "ダ"
            word.partOfSpeech = P.Auxiliary
        } else if (word.text == "だー") {
            word.dictionaryForm = "です"
            word.partOfSpeech = P.Auxiliary
        }

        // したり after を/が is する + ～たり (キスをしたり), not the interjection; an interjection never follows a case particle.
        if (word.text == "したり" && i > 0 && (wordInfos[i - 1].text == "を" || wordInfos[i - 1].text == "が")) {
            word.partOfSpeech = P.Verb
            word.dictionaryForm = "する"
            word.normalizedForm = "為る"
            word.preMatchedWordId = 1157170
            word.preMatchedReadingIndex = 1
            word.preMatchedConjugations = mutableListOf("tari", "(unstressed infinitive)")
        }
        i--
    }
    return wordInfos
}

private val SarauDredgeContext = setOf("川", "池", "溝", "ドブ", "どぶ", "底", "川底", "泥", "井戸", "砂", "堀")
private val SarauReviewContext = setOf("記憶", "情報", "復習", "稽古", "練習", "台詞", "セリフ", "楽譜", "曲", "資料", "内容")

private fun isListParticle(w: WordInfo) = w.partOfSpeech == P.Particle && (w.text == "も" || w.text == "と" || w.text == "や")

private fun isSingleKanjiNoun(w: WordInfo) =
    w.text.length == 1 && JapaneseTextHelper.isKanji(w.text[0]) && w.isNounOrCommon()

private fun MorphologicalAnalyser.tryGetRenyoukeiVerb(stem: String): String? {
    if (stem.isEmpty()) return null
    for (f in pipelineCachedDeconjugate(normalizeToHiragana(stem)))
        if ("stem-ren" in f.tags && f.tags.any { it.startsWith('v') } && hasVerbOrAdjectiveLookup?.invoke(f.text) == true)
            return f.text
    return null
}

private fun MorphologicalAnalyser.pinVerb(
    word: WordInfo, wordId: Int, lemma: String, conjugationSurface: String, conjugationBase: String? = null,
) {
    word.partOfSpeech = P.Verb
    word.dictionaryForm = lemma
    word.normalizedForm = lemma
    word.preMatchedWordId = wordId
    word.preMatchedConjugations = pinnedConjugationProcess(conjugationSurface, conjugationBase ?: lemma)
}

private fun absorbNext(wordInfos: MutableList<WordInfo>, i: Int, reading: String) {
    val word = wordInfos[i]
    val next = wordInfos[i + 1]
    word.text += next.text
    word.reading = reading
    word.endOffset = next.endOffset
    wordInfos.removeAt(i + 1)
}

private fun clearPosSections(word: WordInfo) {
    word.partOfSpeechSection1 = PartOfSpeechSection.None
    word.partOfSpeechSection2 = PartOfSpeechSection.None
    word.partOfSpeechSection3 = PartOfSpeechSection.None
}

private fun atClauseStart(wordInfos: List<WordInfo>, i: Int) = isClauseBoundary(if (i > 0) wordInfos[i - 1] else null)

// Unlike IsClauseBoundary, a following space does not end the clause here.
private fun followedByPunctuationOrEnd(wordInfos: List<WordInfo>, i: Int) =
    i + 1 >= wordInfos.size ||
        wordInfos[i + 1].partOfSpeech == P.SupplementarySymbol || wordInfos[i + 1].partOfSpeech == P.Symbol

private fun nextNonSplitMarker(wordInfos: List<WordInfo>, i: Int): Int {
    var j = i + 1
    while (j < wordInfos.size && (wordInfos[j].text == "|" || wordInfos[j].text == "")) j++
    return j
}

// A surface that reaches a lookup entry resolves through normal scoring; a pin would only degrade it.
private fun MorphologicalAnalyser.kanaSurfaceResolvesViaLookup(word: WordInfo): Boolean {
    val lookup = hasNonNameCompoundLookup ?: return false
    if (word.dictionaryForm.isNotEmpty() && lookup(word.dictionaryForm)) return true
    val hira = normalizeToHiragana(word.text)
    if (lookup(hira)) return true
    for (form in pipelineCachedDeconjugate(hira))
        if (form.text.length >= 3 && lookup(form.text)) return true
    return false
}

/** A PreMatchedWordId pin skips deconjugation, so without this chain every pin emits a bare lemma. Shared (RepairStages, RewriteRules). */
internal fun MorphologicalAnalyser.pinnedConjugationProcess(surface: String, dictionaryForm: String): MutableList<String>? {
    val hiraSurface = normalizeToHiragana(surface)
    val hiraDict = normalizeToHiragana(dictionaryForm)
    if (hiraSurface == hiraDict) return null
    for (form in pipelineCachedDeconjugate(hiraSurface)) if (form.text == hiraDict) return form.process.toMutableList()
    return null
}

/** Fixes Sudachi readings of kanji homographs from context (directional 表へ → おもて, not ひょう). */
internal fun MorphologicalAnalyser.fixReadingAmbiguity(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    var i = 0
    while (i < wordInfos.size) {
        val word = wordInfos[i]

        if (word.text == "何" && word.reading == "ナン") {
            val next = wordInfos.getOrNull(i + 1)
            if (next == null || next.text == "を" || next.text == "が" || next.text == "も")
                word.reading = "ナニ"
        }

        // Suffix ばり binds to an adjacent nominal (漱石張り); after a particle or clause start it is はり (声に張りがある).
        if (word.text == "張り" && word.reading == "バリ") {
            // A quoted host still binds (「漱石」張り), so skip closing brackets and inserted blanks, not punctuation.
            var h = i - 1
            while (h >= 0 && (wordInfos[h].partOfSpeech == P.BlankSpace ||
                    (wordInfos[h].text.length == 1 && wordInfos[h].text[0] in "」』）】》〉")))
                h--
            val prevHost = if (h >= 0) wordInfos[h] else null
            if (prevHost == null ||
                prevHost.partOfSpeech !in setOf(P.Noun, P.CommonNoun, P.Name, P.Pronoun, P.Numeral, P.Suffix))
                word.reading = "ハリ"
        }

        // 寒気 is サムケ "chills" when felt (寒気がする, 寒気を覚える); 寒気が南下する stays カンキ "cold air".
        if (word.text == "寒気" && word.reading == "カンキ" && i + 2 < wordInfos.size &&
            ((wordInfos[i + 1].text == "が" && wordInfos[i + 2].dictionaryForm == "する") ||
                (wordInfos[i + 1].text == "を" && wordInfos[i + 2].dictionaryForm in setOf("覚える", "感じる", "催す"))))
            word.reading = "サムケ"

        i = fixNumeralReadings(wordInfos, i)

        // 弾く is はじく (グラスを弾いた) unless a music word is near (ピアノが弾かれた); ヒケ* is left to the 弾ける block.
        if (word.dictionaryForm == "弾く" && word.reading.startsWith("ヒ") && !word.reading.startsWith("ヒケ")) {
            var musicContext = false
            var k = maxOf(0, i - 6)
            while (k < minOf(wordInfos.size, i + 4) && !musicContext) {
                musicContext = wordInfos[k].text in MusicWords
                k++
            }
            if (!musicContext)
                word.reading = "ハジ" + word.reading.substring(1)
        }

        // Sudachi reads every 素振り スブリ; "practice swing" needs a swing word nearby, else the scorer keeps そぶり.
        if (word.text == "素振り" && word.reading == "スブリ") {
            var swingContext = false
            var k = maxOf(0, i - 6)
            while (k < minOf(wordInfos.size, i + 7) && !swingContext) {
                val t = wordInfos[k].text
                swingContext = t in SwingWords || t.contains("バット") || t.contains("バッター") ||
                    t.contains("ラケット") || t.contains("スイング")
                k++
            }
            if (swingContext)
                word.preMatchedWordId = 1749550
        }

        // Sudachi drops っ from some lemmas (かっこつけ→かこつける); onbin chains (言って→言う) keep their lemma.
        if (word.partOfSpeech == P.Verb && word.preMatchedWordId == null && word.text.contains('っ') &&
            word.dictionaryForm.isNotEmpty() && !word.dictionaryForm.contains('っ')) {
            val tsuForms = pipelineCachedDeconjugate(word.text)
            val currentLemma = word.dictionaryForm
            val tsuBetter = if (tsuForms.any { it.text == currentLemma }) null
            else tsuForms.firstOrNull { f ->
                f.text.contains('っ') && f.tags.any { it.startsWith("v") } &&
                    hasNonNameCompoundLookup?.invoke(f.text) == true
            }
            if (tsuBetter != null) {
                word.dictionaryForm = tsuBetter.text
                word.normalizedForm = tsuBetter.text
            }
        }

        // 札 is ふだ only with a game word AND a card verb; money changes hands in game scenes (麻雀に負けて札を渡した).
        if (word.text == "札" && word.reading == "サツ") {
            val cardVerb = i + 2 < wordInfos.size && wordInfos[i + 1].text == "を" &&
                wordInfos[i + 2].dictionaryForm in setOf("出す", "出せる", "引く", "切る", "並べる", "配る", "見る")
            var gameContext = false
            var k = maxOf(0, i - 30)
            while (k < minOf(wordInfos.size, i + 5) && !gameContext) {
                gameContext = wordInfos[k].text in setOf("麻雀", "トランプ", "カード", "手札", "切り札", "カルタ")
                k++
            }
            if (cardVerb && gameContext)
                word.reading = "フダ"
        }

        // Sudachi lemmatises 弄った to archaic 弄う (いらう); modern usage is 弄る (いじる).
        if (word.dictionaryForm == "弄う") {
            word.dictionaryForm = "弄る"
            word.normalizedForm = "弄る"
            word.reading = word.reading.replace("イラ", "イジ")
        }

        // Sudachi always reads 角 カド; context picks つの (鬼の角, 角が生える) or かく (三角形の角, 角が90度).
        if (word.text == "角" && word.reading == "カド") {
            val next = wordInfos.getOrNull(i + 1)
            val prev = if (i > 0) wordInfos[i - 1] else null
            val next2 = wordInfos.getOrNull(i + 2)
            val prev2 = if (i >= 2) wordInfos[i - 2] else null

            val isHornVerb = next != null && (next.text == "が" || next.text == "を") && next2 != null &&
                next2.dictionaryForm in setOf("生える", "生やす", "折れる", "折る", "研ぐ", "磨く")
            val afterCreature = prev?.text == "の" && prev2 != null && isHornBearerWord(prev2.text)
            val afterHead = prev != null && (prev.text == "に" || prev.text == "の") && prev2 != null &&
                prev2.text in setOf("頭", "額", "おでこ")
            val afterGeometry = prev?.text == "の" && prev2 != null &&
                (prev2.text.endsWith("角形") || prev2.text.endsWith("多角"))
            val next3 = wordInfos.getOrNull(i + 3)
            val beforeDegree = next != null && next.text in setOf("が", "は", "も") && next2 != null &&
                (next2.text.contains('度') || next2.dictionaryForm == "等しい" ||
                    (isNumeralToken(next2) && next3?.text == "度"))

            if (isHornVerb || afterCreature || afterHead)
                word.reading = "ツノ"
            else if (afterGeometry || beforeDegree)
                word.reading = "カク"
        }

        // 額 is ヒタイ in body-contact context (額にキスをした, 額の傷).
        if (word.text == "額" && word.reading == "ガク") {
            val next = wordInfos.getOrNull(i + 1)
            val next2 = wordInfos.getOrNull(i + 2)
            val isBodyContext = next != null && next.text in setOf("に", "を", "の") && next2 != null &&
                (next2.dictionaryForm in setOf("キス", "触れる", "当てる", "押す", "押さえる", "叩く", "撫でる", "拭く") ||
                    next2.text in setOf("手", "キス", "汗", "傷", "皺", "シワ"))
            if (isBodyContext)
                word.reading = "ヒタイ"
        }

        // いだく is literary; modern standalone 抱く is overwhelmingly だく.
        if (word.dictionaryForm == "抱く" && word.reading.startsWith("イダ"))
            word.reading = word.reading.replace("イダ", "ダ")

        // Kana よう as 形状詞/助動詞語幹 is 様 "manner", not 陽 "positive".
        if (word.text == "よう" && word.reading == "ヨウ" && word.dictionaryForm == "よう")
            word.preMatchedWordId = 1605840

        // ジ only occurs inside kango compounds (仕事); an orphaned 事 is the nominaliser こと.
        if (word.text == "事" && word.reading == "ジ" && word.wasReclassifiedFromSuffix)
            word.reading = "コト"

        // たった after a time unit (三年たった) is 経つ, not 断つ/立つ.
        if (word.text == "たった" && word.partOfSpeech in setOf(P.Verb, P.Auxiliary, P.Unknown)) {
            val prev = if (i > 0) wordInfos[i - 1] else null
            if (prev != null && prev.partOfSpeech != P.SupplementarySymbol) {
                val precedingIsTimeUnit = prev.text.endsWith('年') || prev.text.endsWith('月') ||
                    prev.text.endsWith('日') || prev.text.endsWith('週') || prev.text.endsWith('間')
                if (precedingIsTimeUnit) {
                    word.preMatchedWordId = 1251100
                    word.dictionaryForm = "たつ"
                    word.preMatchedConjugations = mutableListOf("past")
                }
            }
        }

        // A kanji noun directly before a verb is an ichidan 連用中止 stem (体を支え立ち上がった: 支える, not 支え).
        if (word.partOfSpeech == P.Noun &&
            word.dictionaryForm == word.text &&
            word.preMatchedWordId == null &&
            word.text.length >= 2 &&
            word.text.any(JapaneseTextHelper::isKanji) && // KanaScoringHelpers.ContainsKanji
            isIchidanStemEnding(word.reading) &&
            i + 1 < wordInfos.size &&
            wordInfos[i + 1].partOfSpeech == P.Verb &&
            // A suru-noun before できる is the potential pattern (真似できない), not a verb stem.
            !((wordInfos[i + 1].dictionaryForm == "できる" || wordInfos[i + 1].dictionaryForm == "出来る") &&
                hasSuruVerbCompoundLookup?.invoke(word.text) == true)) {
            word.partOfSpeech = P.Verb
            word.dictionaryForm = word.text + "る"
        }

        // 露 before になる is あらわ "exposed" (服が露になった); Sudachi's ロ reading drags scoring to dew/Russia.
        if (word.text == "露" && word.partOfSpeech == P.Noun && i + 2 < wordInfos.size &&
            wordInfos[i + 1].text == "に" &&
            (wordInfos[i + 2].dictionaryForm == "なる" || wordInfos[i + 2].dictionaryForm == "成る")) {
            word.reading = "アラワ"
            word.normalizedForm = "露わ"
        }

        // Clause-final あり after に/と is classical ある (貴女と共にあり――); the ant 蟻 takes が/は/を.
        if (word.text == "あり" && word.partOfSpeech == P.Noun &&
            i > 0 && (wordInfos[i - 1].text.endsWith('に') || wordInfos[i - 1].text.endsWith('と')) &&
            isClauseBoundary(wordInfos.getOrNull(i + 1))) {
            word.partOfSpeech = P.Verb
            word.dictionaryForm = "ある"
        }

        // Standalone 捩る is ねじる "twist"; もじる "parody" is rare and usually kana.
        if (word.dictionaryForm == "捩る" && word.reading.startsWith("モジ"))
            word.reading = word.reading.replace("モジ", "ネジ")

        // 大勢 is オオゼイ "many people"; タイセイ "trend" only in set phrases (大勢に影響がない).
        if (word.text == "大勢" && word.reading == "タイセイ") {
            val next = wordInfos.getOrNull(i + 1)
            val next2 = wordInfos.getOrNull(i + 2)
            val isTaiseiContext = next?.text == "に" && next2?.dictionaryForm == "影響"
            if (!isTaiseiContext)
                word.reading = "オオゼイ"
        }

        // Sudachi maps katakana イキ to 生きる, which is never written イキ; it is slang 行く (イキました).
        if (word.dictionaryForm == "イキる" && word.text.startsWith("イキ")) {
            word.dictionaryForm = "行く"
            word.normalizedForm = "行く"
        }

        // Kana いける is 行ける "go well" unless a flower object precedes (花をいける → 生ける).
        if (word.text == "いける" && word.dictionaryForm == "いける") {
            val prevTok = if (i > 0) wordInfos[i - 1] else null
            val prev2Tok = if (i >= 2) wordInfos[i - 2] else null
            val flowerContext =
                (prevTok != null && (prevTok.text.contains('花') || prevTok.text.contains('華'))) ||
                    (prev2Tok != null && (prev2Tok.text.contains('花') || prev2Tok.text.contains('華')))
            // DictionaryForm splits the context-blind DeckWord cache key; else the first sense parsed wins every いける.
            word.preMatchedWordId = if (flowerContext) 1587190 else 1631370
            word.dictionaryForm = if (flowerContext) "生ける" else "行ける"
        }

        // Katakana ツイてる is 付いてる "lucky"; ついて "about" is never written in katakana.
        if (word.text.startsWith("ツイて")) {
            word.preMatchedWordId = 1894260
            word.dictionaryForm = "ツイてる"
            // The pin skips deconjugation; without an explicit chain ツイてた renders as the bare lemma.
            word.preMatchedConjugations = when (word.text.substring("ツイて".length)) {
                "なかった" -> mutableListOf("negative", "past")
                "ない" -> mutableListOf("negative")
                "た" -> mutableListOf("past")
                else -> word.preMatchedConjugations
            }
        }

        // Sudachi lemmatises 弾く's potential as 弾ける too; reading ヒケ* marks the potential.
        if (word.dictionaryForm == "弾ける" && word.reading.startsWith("ヒケ")) {
            word.dictionaryForm = "弾く"
            word.normalizedForm = "弾く"
        }

        // A キタ reading on 来 (来た, or Sudachi's 文語 来たる) makes ReadingMatchScore pick きたる; 来り is genuinely きたる.
        if (word.normalizedForm == "来たる" && (word.text == "来る" || word.text == "来")) {
            word.reading = word.reading.replace("キタ", "ク")
            word.normalizedForm = "来る"
            word.dictionaryForm = "来る"
        } else if (word.text.length >= 2 && word.text[0] == '来' && word.text[1] != 'り' &&
            word.dictionaryForm == "来る" && word.reading.startsWith("キタ")) {
            word.reading = "ク" + word.reading.substring(2)
        }

        // Clause-initial よって、 is "therefore"; 場合によって keeps the verb. DictionaryForm splits the context-blind cache key.
        if (word.text == "よって" && word.dictionaryForm in setOf("依る", "因る", "よる") &&
            atClauseStart(wordInfos, i) &&
            i + 1 < wordInfos.size && (wordInfos[i + 1].text == "、" || wordInfos[i + 1].text == "，")) {
            word.preMatchedWordId = 1605970
            word.dictionaryForm = "よって"
        }
        i++
    }
    return wordInfos
}

private val MusicWords = setOf("ピアノ", "ギター", "エレキギター", "バイオリン", "ヴァイオリン", "曲", "演奏", "コンサート", "音楽", "弦", "楽器", "バンド")
private val SwingWords = setOf("竹刀", "木刀", "刀", "剣", "剣道", "野球", "打席", "練習", "稽古", "部活", "ゴルフ", "テニス")

private fun isIchidanStemEnding(reading: String): Boolean {
    if (reading.isEmpty()) return false
    return reading.last() in "エケセテネベメレゲペヘゼイキシチニビミリギピヒジ"
}

private val HornBearers = setOf(
    "鬼", "牛", "鹿", "羊", "山羊", "馬", "竜", "龍",
    "悪魔", "怪物", "獣", "魔物", "魔族", "動物",
    "トナカイ", "ドラゴン", "モンスター", "ユニコーン", "サイ",
    "カブトムシ", "クワガタ", "虫", "デーモン",
)

private fun isHornBearerWord(text: String) = text in HornBearers
