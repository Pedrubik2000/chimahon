// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Stages/MorphologicalAnalyser.RepairStages.cs
// (second half, C# lines 2022-4486: ProcessSpecialCases .. RecombineHiraganaTokens)
package chimahon.lang.jiten

// Helpers suffixed _p2 are file-private copies of C#-private helpers from the first half of the C# file (lines 1-2021).

/** C# line 2009: なく belongs to a bound auxiliary verb stem after a て-form (出て+こ+なく), not to a following なった. */
private fun isBoundAuxiliaryNegative_p2(w1: WordInfo, emitted: List<WordInfo>): Boolean {
    if (w1.text != "なく" || emitted.size < 2) return false

    val stem = emitted[emitted.size - 1]
    if (stem.partOfSpeech != PartOfSpeech.Verb || !stem.hasPartOfSpeechSection(PartOfSpeechSection.PossibleDependant))
        return false

    val teForm = emitted[emitted.size - 2]
    return teForm.text.endsWith('て') || teForm.text.endsWith('で')
}

/** C# line 1787. */
private fun MorphologicalAnalyser.deconjugatesToVerbInLookup_p2(surface: String): Boolean {
    val lookup = hasVerbOrAdjectiveLookup ?: return false
    for (f in Deconjugator.instance.deconjugate(normalizeToHiragana(surface)))
        if (f.tags.any { it.startsWith("v") } && lookup(f.text)) return true
    return false
}

/** C# line 1797. Stricter than DeconjugatesToVerbInLookup so plain ichidan negatives (聞こえない, 信用ない) stay split. */
private fun MorphologicalAnalyser.deconjugatesToCausativeOrPassiveVerb_p2(surface: String): Boolean {
    val lookup = hasVerbOrAdjectiveLookup ?: return false
    for (f in Deconjugator.instance.deconjugate(normalizeToHiragana(surface)))
        if (f.tags.any { it.startsWith("v") } && lookup(f.text) &&
            f.process.any { p -> "causative" in p || "passive" in p })
            return true
    return false
}

private fun isNounOrCommonNoun(p: PartOfSpeech) = p == PartOfSpeech.Noun || p == PartOfSpeech.CommonNoun

internal fun MorphologicalAnalyser.processSpecialCases(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.isEmpty()) return wordInfos

    val newList = ArrayList<WordInfo>(wordInfos.size)

    var i = 0
    while (i < wordInfos.size) {
        val w1 = wordInfos[i]

        // Kana したんだ with dict form したむ is Sudachi's misread of した+んだ; the dated verb 湑む is written in kanji.
        if (w1.dictionaryForm == "したむ" && w1.text.startsWith("した") && w1.text.length >= 3) {
            val rest = w1.text.substring(2)
            val mid = if (w1.startOffset >= 0) w1.startOffset + 2 else -1
            // Unpinned, CombineAuxiliary re-merges したんだ and re-derives 湑む; pins bypass deconjugation, hence the chain.
            val conj = pinnedConjugationProcess("した", "する")
            newList.add(w1.copy().apply {
                text = "した"; dictionaryForm = "する"; normalizedForm = "為る"
                partOfSpeech = PartOfSpeech.Verb; reading = "シタ"
                preMatchedWordId = 1157170; preMatchedConjugations = conj
                endOffset = mid
            })
            newList.add(w1.copy().apply {
                text = rest; dictionaryForm = rest; normalizedForm = rest
                partOfSpeech = PartOfSpeech.Auxiliary
                reading = if (w1.reading.length > 2) w1.reading.substring(2) else ""
                preMatchedWordId = if (rest == "んだ") 2849387 else null
                startOffset = mid; endOffset = w1.endOffset
            })
            i += 1
            continue
        }

        // それっぽく → そ|れっぽく… blob; the remainder re-tokenises in RetokeniseOovBlobs. The OOV gate spares real そ+れX.
        if (w1.text == "そ" && i + 1 < wordInfos.size &&
            wordInfos[i + 1].text.length >= 3 && wordInfos[i + 1].text[0] == 'れ' &&
            hasNonNameCompoundLookup?.invoke(wordInfos[i + 1].text) == false) {
            val blob = wordInfos[i + 1]
            val rest = blob.text.substring(1)
            val mid = if (w1.endOffset >= 0) w1.endOffset + 1 else -1
            newList.add(w1.copy().apply {
                text = "それ"; dictionaryForm = "それ"; normalizedForm = "其れ"
                partOfSpeech = PartOfSpeech.Pronoun; reading = "ソレ"
                preMatchedWordId = 1006970; endOffset = mid
            })
            wordInfos[i + 1] = blob.copy().apply {
                text = rest; dictionaryForm = rest; normalizedForm = rest; startOffset = mid
            }
            i += 1
            continue
        }

        // 募|るってもんじゃねえ: a colloquial tail blobs the る; the OOV gate spares genuine 名詞+る words.
        if (w1.text.length == 1 && JapaneseTextHelper.isKanji(w1.text[0]) &&
            isNounOrCommonNoun(w1.partOfSpeech) &&
            i + 1 < wordInfos.size && wordInfos[i + 1].text.length >= 3 &&
            wordInfos[i + 1].text[0] == 'る' &&
            hasNonNameCompoundLookup?.invoke(wordInfos[i + 1].text) == false &&
            hasNonNameCompoundLookup?.invoke(w1.text + "る") == true) {
            val blob = wordInfos[i + 1]
            val rest = blob.text.substring(1)
            val mid = if (w1.endOffset >= 0) w1.endOffset + 1 else -1
            newList.add(w1.copy().apply {
                text = w1.text + "る"; dictionaryForm = w1.text + "る"; normalizedForm = w1.text + "る"
                partOfSpeech = PartOfSpeech.Verb; reading = ""
                endOffset = mid
            })
            wordInfos[i + 1] = blob.copy().apply {
                text = rest; dictionaryForm = rest; normalizedForm = rest; startOffset = mid
            }
            i += 1
            continue
        }

        // 四つ目 / みっつめ is numeral + ordinal 目, not "four-eyed" or 見詰める; kana numerals are a closed set.
        val kanjiTsuMe = w1.text.length >= 3 && w1.text.endsWith("つ目") && takesOrdinalMeAfterTsu(w1.text[0])
        val kanaTsuMe = w1.text.endsWith("つめ") &&
            w1.text.dropLast(1) in setOf("ひとつ", "ふたつ", "みっつ", "よっつ", "いつつ", "むっつ", "ななつ", "やっつ", "ここのつ")
        if ((isNounOrCommonNoun(w1.partOfSpeech) || w1.partOfSpeech == PartOfSpeech.Verb) && (kanjiTsuMe || kanaTsuMe)) {
            val numPart = w1.text.dropLast(1) // 四つ / みっつ
            val meText = w1.text.takeLast(1) // 目 / め
            val mid = if (w1.startOffset >= 0) w1.startOffset + numPart.length else -1
            newList.add(w1.copy().apply {
                text = numPart; dictionaryForm = numPart; normalizedForm = numPart
                partOfSpeech = PartOfSpeech.Noun
                reading = if (w1.reading.isNotEmpty()) w1.reading.dropLast(1) else ""; endOffset = mid
            })
            newList.add(WordInfo().apply {
                text = meText; dictionaryForm = "目"; normalizedForm = "目"
                partOfSpeech = PartOfSpeech.Suffix; reading = "メ"
                preMatchedWordId = 1604890; preMatchedReadingIndex = 0; hardPinned = true
                startOffset = mid; endOffset = w1.endOffset
            })
            i += 1
            continue
        }

        // 部|下達 → 部下|達 needs a 1-char X (事実|上達 stays) and a common compound, not a coincidental lookup key.
        val w2tachi = wordInfos.getOrNull(i + 1)
        if (isNounOrCommonNoun(w1.partOfSpeech) &&
            w1.text.length == 1 &&
            w2tachi != null && isNounOrCommonNoun(w2tachi.partOfSpeech) &&
            w2tachi.text.length >= 2 && w2tachi.text.endsWith("達") &&
            hasNonNameCompoundLookup?.invoke(w1.text + w2tachi.text.dropLast(1)) == true &&
            getNonNameCompoundFrequencyRank?.invoke(w1.text + w2tachi.text.dropLast(1)).let { it != null && it < 40000 }) {
            val compound = w1.text + w2tachi.text.dropLast(1)
            val mid = if (w2tachi.endOffset >= 0) w2tachi.endOffset - 1 else -1
            newList.add(w1.copy().apply {
                text = compound; dictionaryForm = compound; normalizedForm = compound
                reading = ""; endOffset = mid
            })
            newList.add(w2tachi.copy().apply {
                text = "達"; dictionaryForm = "達"; normalizedForm = "達"
                partOfSpeech = PartOfSpeech.Suffix; reading = "タチ"; startOffset = mid
            })
            i += 2
            continue
        }

        // お|前山 → お前|山; ゼン compounds (前後), common ones (この|前髪) and 前+連用形 (前掛け) stay whole.
        // Gated on rank because JMDict priority tags mislead (前山's homograph ぜんざん carries news tags).
        val rankLookup = getNonNameCompoundFrequencyRank
        if (w1.text.length >= 2 && w1.text[0] == '前' &&
            isNounOrCommonNoun(w1.partOfSpeech) &&
            !w1.reading.startsWith("ゼン") &&
            rankLookup != null &&
            rankLookup(w1.text).let { it == null || it >= 40000 } &&
            newList.size > 0 &&
            hasNonNameCompoundLookup?.invoke(newList[newList.size - 1].text + "前") == true &&
            hasNonNameCompoundLookup?.invoke(w1.text.substring(1)) == true &&
            renyokeiSurfaceToVerb(w1.text.substring(1)) == null) {
            val prev = newList[newList.size - 1]
            val rest = w1.text.substring(1)
            val mid = if (w1.startOffset >= 0) w1.startOffset + 1 else -1
            newList[newList.size - 1] = prev.copy().apply {
                text = prev.text + "前"; dictionaryForm = prev.text + "前"; normalizedForm = prev.text + "前"
                reading = ""; endOffset = mid
            }
            newList.add(w1.copy().apply {
                text = rest; dictionaryForm = rest; normalizedForm = rest
                reading = ""; startOffset = mid
            })
            i += 1
            continue
        }

        // OOV 親X: a 1-char rest is the "pro-" prefix (親ソ, 親米); a longer rest means parent (親ギツネ).
        if (w1.text.length >= 2 && w1.text[0] == '親' &&
            isNounOrCommonNoun(w1.partOfSpeech) &&
            hasNonNameCompoundLookup?.invoke(w1.text) == false &&
            hasNonNameCompoundLookup?.invoke(w1.text.substring(1)) == true) {
            val rest = w1.text.substring(1)
            // A hiragana rest is a colloquial fragment where 親 means parent.
            val isProPrefix = rest.length == 1 && rest[0] !in 'ぁ'..'ゟ'
            val mid = if (w1.startOffset >= 0) w1.startOffset + 1 else -1
            newList.add(w1.copy().apply {
                text = "親"; dictionaryForm = "親"; normalizedForm = "親"
                partOfSpeech = if (isProPrefix) PartOfSpeech.Prefix else PartOfSpeech.Noun
                reading = if (isProPrefix) "シン" else "オヤ"
                preMatchedWordId = if (isProPrefix) 2256340 else null; endOffset = mid
            })
            newList.add(w1.copy().apply {
                text = rest; dictionaryForm = rest; normalizedForm = rest
                reading = ""; startOffset = mid
                // Generic nouns come first in ソ's lookup, so the Soviet Union abbreviation needs a pin.
                preMatchedWordId = if (rest == "ソ") 2853158 else null
            })
            i += 1
            continue
        }

        // A single OOV 〜くも token (美しくも) is adverbial 〜く + も.
        if (w1.text.length >= 4 && w1.text.endsWith("くも") &&
            hasCompoundLookup?.invoke(w1.text) != true &&
            Deconjugator.instance.deconjugate(normalizeToHiragana(w1.text.dropLast(1)))
                .any { f -> f.tags.any { it == "adj-i" } && hasNonNameCompoundLookup?.invoke(f.text) == true }) {
            val kuPart = w1.text.dropLast(1)
            val mid = if (w1.startOffset >= 0) w1.startOffset + kuPart.length else -1
            newList.add(w1.copy().apply {
                text = kuPart; dictionaryForm = kuPart; normalizedForm = kuPart
                partOfSpeech = PartOfSpeech.IAdjective; reading = ""; endOffset = mid
            })
            newList.add(WordInfo().apply {
                text = "も"; dictionaryForm = "も"; normalizedForm = "も"
                partOfSpeech = PartOfSpeech.Particle; reading = "モ"
                startOffset = mid; endOffset = w1.endOffset
            })
            i += 1
            continue
        }

        // 当たれ → あ|たれ(垂れ); must run before CombineVerbDependant steals the あ (持って+あ).
        val w2at = wordInfos.getOrNull(i + 1)
        if (w1.text == "あ" && w1.partOfSpeech == PartOfSpeech.Interjection &&
            w2at != null && w2at.partOfSpeech == PartOfSpeech.Noun && w2at.text.length >= 2 &&
            deconjugatesToVerbInLookup_p2("あ" + w2at.text)) {
            newList.add(w1.copy().apply {
                text = "あ" + w2at.text; dictionaryForm = "あ" + w2at.text; normalizedForm = "あ" + w2at.text
                partOfSpeech = PartOfSpeech.Verb; reading = "ア" + w2at.reading
                endOffset = w2at.endOffset
            })
            i += 2
            continue
        }

        // Causative stem tagged a noun before ない (やらせ|ない); plain 仕方|ない stays split.
        if (isNounOrCommonNoun(w1.partOfSpeech) &&
            i + 1 < wordInfos.size && wordInfos[i + 1].text == "ない" &&
            deconjugatesToCausativeOrPassiveVerb_p2(w1.text + "ない")) {
            val nai = wordInfos[i + 1]
            newList.add(w1.copy().apply {
                text = w1.text + "ない"; dictionaryForm = w1.text + "ない"; normalizedForm = w1.text + "ない"
                partOfSpeech = PartOfSpeech.Verb; reading = w1.reading + nai.reading
                endOffset = nai.endOffset
            })
            i += 2
            continue
        }

        // A following ね re-cuts 静か into 静|か(終助詞); the lookup gate spares real noun + question か.
        if (w1.partOfSpeech == PartOfSpeech.Noun &&
            w1.hasPartOfSpeechSection(PartOfSpeechSection.PossibleNaAdjective) &&
            i + 1 < wordInfos.size &&
            wordInfos[i + 1].let { it.dictionaryForm == "か" && it.partOfSpeech == PartOfSpeech.Particle } &&
            (hasNonNameCompoundLookup ?: hasCompoundLookup)?.invoke(w1.text + "か") == true) {
            val ka = wordInfos[i + 1]
            newList.add(w1.copy().apply {
                text = w1.text + "か"
                dictionaryForm = w1.text + "か"
                normalizedForm = w1.text + "か"
                partOfSpeech = PartOfSpeech.NaAdjective
                reading = ""
                endOffset = ka.endOffset
            })
            i += 2
            continue
        }

        // A hiragana-dict token steals a katakana name's last mora (カティア|の → カティ|アの, アの read as あの).
        if ((w1.partOfSpeech == PartOfSpeech.PrenounAdjectival || w1.partOfSpeech == PartOfSpeech.Noun) &&
            w1.text.length >= 2 &&
            JapaneseTextHelper.isKatakanaWordChar(w1.text[0]) && w1.text[0] != 'ー' &&
            w1.text.substring(1).all { it in '぀'..'ゟ' } &&
            w1.text.substring(1) in CaseParticles &&
            KanaConverter.toHiragana(w1.text) == w1.dictionaryForm &&
            newList.size > 0 &&
            (newList[newList.size - 1].partOfSpeech == PartOfSpeech.Noun || newList[newList.size - 1].partOfSpeech == PartOfSpeech.Name) &&
            JapaneseTextHelper.isAllKatakana(newList[newList.size - 1].text)) {
            val prev = newList[newList.size - 1]
            val stolen = w1.text[0].toString()
            val remainder = w1.text.substring(1)
            val mergedText = prev.text + stolen
            val mergedEndOffset = if (prev.endOffset >= 0) prev.endOffset + 1 else prev.endOffset
            newList[newList.size - 1] = prev.copy().apply {
                text = mergedText
                dictionaryForm = mergedText
                normalizedForm = mergedText
                reading = prev.reading + stolen
                endOffset = mergedEndOffset
            }
            newList.add(WordInfo().apply {
                text = remainder; dictionaryForm = remainder; normalizedForm = remainder
                partOfSpeech = PartOfSpeech.Particle
                reading = WanaKana.toKatakana(remainder)
                startOffset = mergedEndOffset
                endOffset = w1.endOffset
            })
            i++
            continue
        }

        // Case が can't follow conjunctive から, so it is がなる "to yell" (頼むからがなるな).
        // Not a user_dic row: a がなる entry would steal ベルが鳴る-type splits lattice-wide.
        if (w1.text == "が" && w1.partOfSpeech == PartOfSpeech.Particle &&
            i + 1 < wordInfos.size &&
            (wordInfos[i + 1].dictionaryForm == "成る" || wordInfos[i + 1].dictionaryForm == "なる") &&
            wordInfos[i + 1].partOfSpeech == PartOfSpeech.Verb &&
            newList.size > 0 && newList[newList.size - 1].text.endsWith("から")) {
            val naru = wordInfos[i + 1]
            newList.add(naru.copy().apply {
                text = "が" + naru.text
                dictionaryForm = "がなる"
                normalizedForm = "がなる"
                reading = "ガ" + naru.reading
                startOffset = w1.startOffset
            })
            i += 2
            continue
        }

        // 太鼓持|ちかい(近い) → 太鼓持ち|かい.
        if (w1.text == "ちかい" && w1.partOfSpeech == PartOfSpeech.IAdjective && w1.normalizedForm == "近い" &&
            newList.size > 0 &&
            newList[newList.size - 1].partOfSpeech == PartOfSpeech.Noun &&
            hasCompoundLookup?.invoke(newList[newList.size - 1].text + "ち") == true) {
            val prev = newList[newList.size - 1]
            prev.text += "ち"
            prev.dictionaryForm = prev.text
            prev.normalizedForm = prev.text
            if (prev.endOffset >= 0) prev.endOffset += 1
            val kaiStart = prev.endOffset
            newList.add(WordInfo().apply {
                text = "かい"
                dictionaryForm = "かい"
                normalizedForm = "かい"
                partOfSpeech = PartOfSpeech.Particle
                reading = "カイ"
                startOffset = kaiStart
                endOffset = w1.endOffset
            })
            i++
            continue
        }

        // Sudachi picks 私大 "private university" over 私|大X (私大好き).
        if (w1.text == "私大" && i + 1 < wordInfos.size &&
            (wordInfos[i + 1].partOfSpeech == PartOfSpeech.Noun || wordInfos[i + 1].partOfSpeech == PartOfSpeech.NaAdjective) &&
            hasNonNameCompoundLookup?.invoke("大" + wordInfos[i + 1].text) == true) {
            val nextW = wordInfos[i + 1]
            newList.add(WordInfo().apply {
                text = "私"; dictionaryForm = "私"; normalizedForm = "私"
                partOfSpeech = PartOfSpeech.Pronoun; reading = "ワタシ"
                startOffset = w1.startOffset; endOffset = if (w1.startOffset >= 0) w1.startOffset + 1 else -1
            })
            newList.add(nextW.copy().apply {
                text = "大" + nextW.text
                dictionaryForm = "大" + nextW.dictionaryForm
                normalizedForm = "大" + nextW.normalizedForm
                reading = "ダイ" + nextW.reading
                startOffset = if (w1.startOffset >= 0) w1.startOffset + 1 else -1
            })
            i += 2
            continue
        }

        // X史|上 → X|史上: 史上 binds tighter than the 史 suffix (人類史上初).
        if (w1.partOfSpeech == PartOfSpeech.Noun && w1.text.length >= 3 && w1.text.endsWith('史') &&
            i + 1 < wordInfos.size &&
            wordInfos[i + 1].let { it.text == "上" && it.partOfSpeech == PartOfSpeech.Suffix } &&
            hasNonNameCompoundLookup?.invoke(w1.text.dropLast(1)) == true) {
            val up = wordInfos[i + 1]
            newList.add(w1.copy().apply {
                text = w1.text.dropLast(1); dictionaryForm = w1.text.dropLast(1); normalizedForm = w1.text.dropLast(1)
                endOffset = if (w1.endOffset >= 0) w1.endOffset - 1 else -1
            })
            newList.add(WordInfo().apply {
                text = "史上"; dictionaryForm = "史上"; normalizedForm = "史上"
                partOfSpeech = PartOfSpeech.Noun; reading = "シジョウ"
                startOffset = if (w1.endOffset >= 0) w1.endOffset - 1 else -1; endOffset = up.endOffset
            })
            i += 2
            continue
        }

        // 使いで "usability" steals the で: 魔法|使いで|も|ない → 魔法使い|でもない.
        if (w1.partOfSpeech == PartOfSpeech.Noun && w1.text.length >= 3 && w1.text.endsWith('で') &&
            i + 1 < wordInfos.size && wordInfos[i + 1].text == "も" &&
            newList.size > 0 && newList[newList.size - 1].partOfSpeech == PartOfSpeech.Noun &&
            hasNonNameCompoundLookup?.invoke(newList[newList.size - 1].text + w1.text.dropLast(1)) == true) {
            val prevNoun = newList[newList.size - 1]
            newList[newList.size - 1] = prevNoun.copy().apply {
                text = prevNoun.text + w1.text.dropLast(1)
                dictionaryForm = prevNoun.text + w1.text.dropLast(1)
                normalizedForm = prevNoun.text + w1.text.dropLast(1)
                endOffset = if (w1.endOffset >= 0) w1.endOffset - 1 else -1
            }
            val naiFollows = i + 2 < wordInfos.size && wordInfos[i + 2].text == "ない"
            newList.add(WordInfo().apply {
                text = if (naiFollows) "でもない" else "でも"
                dictionaryForm = if (naiFollows) "でもない" else "でも"
                normalizedForm = if (naiFollows) "でもない" else "でも"
                partOfSpeech = if (naiFollows) PartOfSpeech.Expression else PartOfSpeech.Conjunction
                reading = if (naiFollows) "デモナイ" else "デモ"
                startOffset = if (w1.endOffset >= 0) w1.endOffset - 1 else -1
                endOffset = if (naiFollows) wordInfos[i + 2].endOffset else wordInfos[i + 1].endOffset
            })
            i += if (naiFollows) 3 else 2
            continue
        }

        // Classical attributive 詮|無き → 詮無き (詮無い via the attributive-き deconj rule).
        if (w1.partOfSpeech == PartOfSpeech.Noun && i + 1 < wordInfos.size &&
            wordInfos[i + 1].text == "無き" &&
            hasNonNameCompoundLookup?.invoke(w1.text + "無い") == true) {
            newList.add(w1.copy().apply {
                text = w1.text + "無き"
                dictionaryForm = w1.text + "無い"
                normalizedForm = w1.text + "無い"
                partOfSpeech = PartOfSpeech.IAdjective
                reading = w1.reading + "ナキ"
                endOffset = wordInfos[i + 1].endOffset
            })
            i += 2
            continue
        }

        // とこかも → と|こか(こく)|も; redistribute to とこ|かも.
        val compoundLookup = hasCompoundLookup
        if (w1.text == "こか" && w1.dictionaryForm == "こく" && w1.partOfSpeech == PartOfSpeech.Verb &&
            newList.size > 0 &&
            i + 1 < wordInfos.size &&
            compoundLookup != null) {
            val prev = newList[newList.size - 1]
            val w2 = wordInfos[i + 1]
            val prevPlusKo = prev.text + "こ"
            val kaPlusNext = "か" + w2.text

            // Overwrites prev's POS/reading, so a coincidental name homograph of prev+こ must not qualify.
            val nonNameLookup = hasNonNameCompoundLookup ?: compoundLookup
            if (nonNameLookup(prevPlusKo) && nonNameLookup(kaPlusNext)) {
                prev.text = prevPlusKo
                prev.dictionaryForm = prevPlusKo
                prev.normalizedForm = prevPlusKo
                prev.partOfSpeech = PartOfSpeech.CommonNoun
                prev.reading += "コ"
                if (prev.endOffset >= 0) prev.endOffset += 1
                val kaStart = prev.endOffset

                newList.add(WordInfo().apply {
                    text = kaPlusNext
                    dictionaryForm = kaPlusNext
                    normalizedForm = kaPlusNext
                    partOfSpeech = PartOfSpeech.Particle
                    reading = "カ" + w2.reading
                    startOffset = kaStart
                    endOffset = w2.endOffset
                })
                i += 2
                continue
            }
        }

        if (w1.partOfSpeech == PartOfSpeech.IAdjective &&
            w1.text.length > 1 && w1.text[0] == 'て' &&
            newList.size > 0 &&
            newList[newList.size - 1].let { it.text == "なん" && it.partOfSpeech == PartOfSpeech.Pronoun }) {
            val remainder = w1.text.substring(1)
            val mid = if (w1.startOffset >= 0) w1.startOffset + 1 else -1
            newList.add(WordInfo().apply {
                text = "て"; dictionaryForm = "て"; normalizedForm = "て"
                partOfSpeech = PartOfSpeech.Particle; reading = "テ"
                startOffset = w1.startOffset; endOffset = mid
            })
            newList.add(WordInfo().apply {
                text = remainder; dictionaryForm = remainder; normalizedForm = remainder
                partOfSpeech = PartOfSpeech.IAdjective; reading = if (w1.reading.length > 1) w1.reading.substring(1) else ""
                startOffset = mid; endOffset = w1.endOffset
            })
            i++
            continue
        }

        if ((w1.partOfSpeech == PartOfSpeech.Conjunction || w1.partOfSpeech == PartOfSpeech.Auxiliary) && w1.text == "で") {
            val nextIsMo = i + 1 < wordInfos.size && wordInfos[i + 1].text == "も"
            if (!nextIsMo) {
                w1.partOfSpeech = PartOfSpeech.Particle
                newList.add(w1)
                i++
                continue
            }
        }

        // Sudachi tags んで/んだ te-forms 表現 when an expression entry exists (飛んで → 2248530 "zero; flying").
        if (w1.partOfSpeech == PartOfSpeech.Expression && w1.text.length >= 3 &&
            (w1.text.endsWith("んで") || w1.text.endsWith("んだ"))) {
            val hiragana = normalizeToHiragana(w1.text)
            val deconjForms = pipelineCachedDeconjugate(hiragana)
            val verbForm = deconjForms.firstOrNull { f ->
                f.tags.any { it == "v5b" || it == "v5m" || it == "v5n" || it == "v5g" } &&
                    (f.text.endsWith('ぶ') || f.text.endsWith('む') || f.text.endsWith('ぬ') || f.text.endsWith('ぐ'))
            }
            if (verbForm != null) {
                val prefix = w1.text.dropLast(2)
                w1.partOfSpeech = PartOfSpeech.Verb
                w1.dictionaryForm = prefix + verbForm.text.last()
            }
        }

        // Suffix 着(ギ) before a particle is the verb 着る, unless it forms a compound (部屋着) for CombineNounCompounds.
        if (w1.text == "着" && w1.partOfSpeech == PartOfSpeech.Suffix && w1.reading == "ギ" &&
            i + 1 < wordInfos.size &&
            (wordInfos[i + 1].partOfSpeech == PartOfSpeech.Particle || wordInfos[i + 1].partOfSpeech == PartOfSpeech.Auxiliary) &&
            !(i > 0 && isNounOrCommonNoun(wordInfos[i - 1].partOfSpeech) &&
                hasNonNameCompoundLookup?.invoke(wordInfos[i - 1].text + "着") == true)) {
            w1.partOfSpeech = PartOfSpeech.Verb
            w1.dictionaryForm = "着る"
            w1.normalizedForm = "着る"
            w1.reading = "キ"
        }

        // 殺し続ける → 殺(Prefix)|し続ける; split to 殺し|続ける.
        val next = wordInfos.getOrNull(i + 1)
        if (w1.partOfSpeech == PartOfSpeech.Prefix && w1.text.length == 1 &&
            WanaKana.isKanji(w1.text) &&
            next != null && next.partOfSpeech == PartOfSpeech.Verb && next.text.length >= 2 &&
            next.text[0] == 'し' &&
            compoundLookup != null &&
            compoundLookup(w1.text + "す") &&
            compoundLookup(next.text.substring(1))) {
            val stemOffsetEnd = if (next.startOffset >= 0) next.startOffset + 1 else -1
            newList.add(WordInfo().apply {
                text = w1.text + "し"
                dictionaryForm = w1.text + "す"
                normalizedForm = w1.text + "す"
                partOfSpeech = PartOfSpeech.Verb
                reading = ""
                startOffset = w1.startOffset
                endOffset = stemOffsetEnd
            })
            newList.add(WordInfo().apply {
                text = next.text.substring(1)
                dictionaryForm = if (next.dictionaryForm.startsWith('し')) next.dictionaryForm.substring(1) else next.text.substring(1)
                normalizedForm = if (next.normalizedForm.startsWith('し')) next.normalizedForm.substring(1) else next.text.substring(1)
                partOfSpeech = PartOfSpeech.Verb
                reading = ""
                startOffset = stemOffsetEnd
                endOffset = next.endOffset
            })
            i += 2
            continue
        }

        // 幼|げ → 幼げ; stays IAdjective so the な handler leaves 的な/がちな alone.
        if (w1.partOfSpeech == PartOfSpeech.Suffix &&
            w1.hasPartOfSpeechSection(PartOfSpeechSection.NaAdjectiveLike) &&
            newList.size > 0 &&
            newList[newList.size - 1].partOfSpeech == PartOfSpeech.IAdjective &&
            !newList[newList.size - 1].text.endsWith('い')) {
            newList[newList.size - 1].text += w1.text
            newList[newList.size - 1].endOffset = w1.endOffset
            i++
            continue
        }

        // のでは is の+では, never ので+は; CombineParticles then forms ではない(か).
        if (w1.text == "の" && i + 2 < wordInfos.size &&
            wordInfos[i + 1].text == "で" && wordInfos[i + 2].text == "は") {
            newList.add(w1)
            i++
            continue
        }

        if (i < wordInfos.size - 2) {
            val w2 = wordInfos[i + 1]
            val w3 = wordInfos[i + 2]

            // Contracted 〜ておこう split as stem|と|こう (ためとこう).
            if (w1.partOfSpeech == PartOfSpeech.Noun &&
                JapaneseTextHelper.isAllHiragana(w1.text) &&
                w2.text == "と" && w2.partOfSpeech == PartOfSpeech.Particle &&
                w3.text == "こう" && w3.partOfSpeech == PartOfSpeech.Adverb &&
                compoundLookup != null) {
                val combined = w1.text + "とこう"
                val forms = pipelineCachedDeconjugate(combined)
                val verbForm = forms.firstOrNull { f ->
                    f.tags.isNotEmpty() && f.tags.size <= 6 &&
                        f.tags.any { it.startsWith('v') } &&
                        compoundLookup(f.text)
                }
                if (verbForm != null) {
                    newList.add(w1.copy().apply {
                        text = combined
                        dictionaryForm = verbForm.text
                        normalizedForm = verbForm.text
                        partOfSpeech = PartOfSpeech.Verb
                        reading = w1.reading + "トコウ"
                        endOffset = w3.endOffset
                    })
                    i += 3
                    continue
                }
            }

            var found = false
            val sc3Candidates = SpecialCases3Dict[w1.text]
            if (sc3Candidates != null && !isBoundAuxiliaryNegative_p2(w1, newList)) {
                for (sc in sc3Candidates) {
                    // RepairVowelElongation may have stripped the particle's trailing ー.
                    val thirdMatch = w3.text == sc.third ||
                        (sc.third.length > 1 && sc.third.last() == 'ー' && w3.text == sc.third.dropLast(1))
                    if (w2.text == sc.second && thirdMatch) {
                        if (newList.size > 0 && compoundLookup != null &&
                            w2.partOfSpeech == PartOfSpeech.Verb) {
                            val prevWord = newList[newList.size - 1]
                            val compoundDictForm = prevWord.text + w1.text + w2.dictionaryForm
                            if (compoundLookup(compoundDictForm)) {
                                newList.removeAt(newList.size - 1)
                                val compoundWord = prevWord.copy()
                                compoundWord.text = prevWord.text + w1.text + w2.text + sc.third
                                compoundWord.endOffset = w3.endOffset
                                compoundWord.dictionaryForm = compoundDictForm
                                compoundWord.partOfSpeech = PartOfSpeech.Verb
                                newList.add(compoundWord)
                                i += 3
                                found = true
                                break
                            }
                        }

                        val newWord = w1.copy()
                        newWord.text = w1.text + w2.text + sc.third
                        newWord.endOffset = w3.endOffset
                        newWord.dictionaryForm = newWord.text

                        if (sc.pos != null) newWord.partOfSpeech = sc.pos

                        newList.add(newWord)
                        i += 3
                        found = true
                        break
                    }
                }
            }

            if (found) continue

            // Explanatory なんだ, except after an aux stem (泣きそうな) or na-adjective (好きなんだ).
            if (w1.text == "な" && w2.text == "ん" && w3.text == "だ") {
                val prevIsAuxVerbStem = i > 0 &&
                    wordInfos[i - 1].hasPartOfSpeechSection(PartOfSpeechSection.AuxiliaryVerbStem)
                val prevIsNaAdjective = i > 0 &&
                    wordInfos[i - 1].partOfSpeech == PartOfSpeech.NaAdjective
                if (!prevIsAuxVerbStem && !prevIsNaAdjective) {
                    val newWord = w1.copy().apply {
                        text = "なんだ"; endOffset = w3.endOffset; dictionaryForm = "なんだ"; partOfSpeech = PartOfSpeech.Auxiliary
                    }
                    newList.add(newWord)
                    i += 3
                    continue
                }
            }

            // そうなんじゃない → そう|なん|じゃない.
            if (w1.text == "な" && w2.text == "ん" && w3.text != "だ" &&
                w2.hasPartOfSpeechSection(PartOfSpeechSection.Juntaijoushi)) {
                val prevIsAuxVerbStem = i > 0 &&
                    wordInfos[i - 1].hasPartOfSpeechSection(PartOfSpeechSection.AuxiliaryVerbStem)
                val prevIsNaAdjective = i > 0 &&
                    wordInfos[i - 1].partOfSpeech == PartOfSpeech.NaAdjective
                if (!prevIsAuxVerbStem && !prevIsNaAdjective) {
                    val newWord = w1.copy().apply {
                        text = "なん"; endOffset = w2.endOffset; dictionaryForm = "なん"; partOfSpeech = PartOfSpeech.Auxiliary
                    }
                    newList.add(newWord)
                    i += 2
                    continue
                }
            }
        }

        if (i < wordInfos.size - 1) {
            val w2 = wordInfos[i + 1]

            // ぶち|キレ → ぶちキレる.
            if (w1.text == "ぶち" && w1.partOfSpeech == PartOfSpeech.Adverb &&
                w2.partOfSpeech == PartOfSpeech.Verb &&
                compoundLookup != null) {
                val compoundDict = "ぶち" + w2.dictionaryForm
                if (compoundLookup(compoundDict)) {
                    val merged = w2.copy()
                    merged.text = "ぶち" + w2.text
                    merged.dictionaryForm = compoundDict
                    merged.normalizedForm = compoundDict
                    merged.startOffset = w1.startOffset
                    merged.reading = "ブチ" + w2.reading
                    newList.add(merged)
                    i += 2
                    continue
                }
            }

            // Verb ん: 飲ん|だけど; explanatory ん stays そう|なんだ|けど.
            val isExplanatoryN = w1.partOfSpeech == PartOfSpeech.Particle &&
                w1.hasPartOfSpeechSection(PartOfSpeechSection.Juntaijoushi)
            if (w1.text == "ん" && w2.text == "だ" && i + 2 < wordInfos.size &&
                wordInfos[i + 2].text in DaCompoundSuffixes &&
                !isExplanatoryN) {
                val w3 = wordInfos[i + 2]
                newList.add(w1)
                val daSuffix = w2.copy().apply { text = w2.text + w3.text; endOffset = w3.endOffset; partOfSpeech = PartOfSpeech.Conjunction }
                newList.add(daSuffix)
                i += 3
                continue
            }

            // Sudachi splits はぐれる as は|ぐれる after で.
            if (w1.text == "は" && w1.partOfSpeech == PartOfSpeech.Particle &&
                w2.partOfSpeech == PartOfSpeech.Verb && w2.dictionaryForm == "ぐれる") {
                w2.text = "は" + w2.text
                w2.startOffset = w1.startOffset
                w2.dictionaryForm = "はぐれる"
                w2.normalizedForm = "はぐれる"
                w2.reading = "ハ" + w2.reading
                newList.add(w2)
                i += 2
                continue
            }

            // 言いたそう → 言|いたそう(いたす); only godan ワ行 verbs (kanji + う) hit this.
            if (w1.partOfSpeech == PartOfSpeech.Noun &&
                w2.text == "いたそう" && w2.dictionaryForm == "いたす" && w2.partOfSpeech == PartOfSpeech.Verb) {
                newList.add(w1.copy().apply {
                    text = w1.text + w2.text
                    endOffset = w2.endOffset
                    partOfSpeech = PartOfSpeech.Verb
                    dictionaryForm = w1.text + "う"
                    normalizedForm = w1.text + "う"
                })
                i += 2
                continue
            }

            // 来|なすった → 来なさる.
            if (w1.text == "来" && w1.partOfSpeech == PartOfSpeech.Verb && w1.dictionaryForm == "来る" &&
                w2.dictionaryForm == "なさる") {
                newList.add(w1.copy().apply {
                    text = w1.text + w2.text; endOffset = w2.endOffset
                    dictionaryForm = "来なさる"; partOfSpeech = PartOfSpeech.Verb
                    reading = "キナスッタ"
                })
                i += 2
                continue
            }

            // そう|いう → そういう (verb dict form kept for そういった); kanji 言う is literal "say" (そう言って).
            if ((w1.partOfSpeech == PartOfSpeech.Adverb ||
                    (w1.partOfSpeech == PartOfSpeech.Interjection && (w1.text == "ああ" || w1.text == "あー"))) &&
                w1.text in setOf("そう", "こう", "ああ", "どう", "そー", "こー", "あー", "どー") &&
                (w2.dictionaryForm == "いう" || w2.dictionaryForm == "言う") &&
                w2.text.isNotEmpty() && w2.text[0] == 'い') {
                newList.add(w1.copy().apply {
                    text = w1.text + w2.text; endOffset = w2.endOffset
                    reading = w1.reading + w2.reading
                    dictionaryForm = w1.text + w2.dictionaryForm
                    partOfSpeech = PartOfSpeech.Verb
                })
                i += 2
                continue
            }

            var found = false
            val sc2Candidates = SpecialCases2Dict[w1.text]
            if (sc2Candidates != null) {
                // か|い stays split when い is a 居る stem with an auxiliary (聞こえているのか|いない).
                val kaIBlocked = (w1.text == "か" || w1.text == "だ") && i + 2 < wordInfos.size &&
                    (w2.dictionaryForm == "居る" || w2.dictionaryForm == "いる") &&
                    wordInfos[i + 2].text in setOf("ない", "なかった", "ます", "た", "て")

                // ところで only sentence-initially or after a past (〜たところで); else locative (静かなところで).
                val tokoroDeBlocked = w1.text == "ところ" && i > 0 &&
                    !(wordInfos[i - 1].text.endsWith("た") || wordInfos[i - 1].text.endsWith("だ"))

                // それじゃない is それ|じゃない, not the conjunction それじゃ.
                val soreJaBlocked = (w1.text == "それ" || w1.text == "そん" || w1.text == "そい") && w2.text == "じゃ" &&
                    i + 2 < wordInfos.size && wordInfos[i + 2].text in setOf("ない", "なかっ", "なかった")

                // 度に "each time" only after a verb (行う度に); after a numeral it is the counter (二度に分けて).
                val doNiBlocked = w1.text == "度" && w2.text == "に" &&
                    !(i > 0 && wordInfos[i - 1].partOfSpeech == PartOfSpeech.Verb)

                // つか must not steal the counter つ after a numeral (三つ|か四つ).
                val tsuKaBlocked = w1.text == "つ" && w2.text == "か" && i > 0 &&
                    (wordInfos[i - 1].partOfSpeech == PartOfSpeech.Numeral ||
                        (wordInfos[i - 1].normalizedForm.isNotEmpty() &&
                            wordInfos[i - 1].normalizedForm.all { it in '0'..'9' }))

                for (sc in sc2Candidates) {
                    if (sc.second == "い" && kaIBlocked) continue
                    if (sc.second == "で" && tokoroDeBlocked) continue
                    if (sc.second == "じゃ" && soreJaBlocked) continue
                    if (sc.second == "に" && doNiBlocked) continue
                    if (sc.second == "か" && tsuKaBlocked) continue
                    if (w2.text == sc.second &&
                        !(sc.pos == PartOfSpeech.Verb && w1.partOfSpeech == PartOfSpeech.Conjunction)) {
                        val newWord = w1.copy().apply { text = w1.text + w2.text; endOffset = w2.endOffset }

                        if (sc.pos == PartOfSpeech.Verb && w1.dictionaryForm.isNotEmpty() && w1.dictionaryForm != w1.text) {
                            newWord.dictionaryForm = w1.dictionaryForm
                        } else {
                            newWord.dictionaryForm = newWord.text
                        }

                        if (sc.pos != null) newWord.partOfSpeech = sc.pos

                        newList.add(newWord)
                        i += 2
                        found = true
                        break
                    }
                }
            }

            if (found) continue
        }

        if (w1.text == "だし" && w1.partOfSpeech != PartOfSpeech.Verb && newList.size > 0) {
            val da = WordInfo().apply {
                text = "だ"; dictionaryForm = "だ"; partOfSpeech = PartOfSpeech.Auxiliary
                partOfSpeechSection1 = PartOfSpeechSection.None; reading = "だ"
                startOffset = w1.startOffset; endOffset = if (w1.startOffset >= 0) w1.startOffset + 1 else -1
            }
            val shi = WordInfo().apply {
                text = "し"; dictionaryForm = "し"; partOfSpeech = PartOfSpeech.Conjunction
                partOfSpeechSection1 = PartOfSpeechSection.None; reading = "し"
                startOffset = if (w1.startOffset >= 0) w1.startOffset + 1 else -1; endOffset = w1.endOffset
            }

            newList.add(da)
            newList.add(shi)
            i++
            continue
        }

        if (w1.text == "な" && w1.dictionaryForm == "だ") {
            val followedByN = i + 1 < wordInfos.size && wordInfos[i + 1].text == "ん"

            // 好き|な|ん|だ|と → 好き|なんだと.
            if (newList.size > 0 && isNaAdjectiveToken(newList[newList.size - 1]) && followedByN) {
                // Plain だ only; conjectural だろ/だろう is a separate grammar point.
                var combined = "な" + wordInfos[i + 1].text
                var j = i + 2
                var lastEndOffset = wordInfos[i + 1].endOffset
                if (j < wordInfos.size && wordInfos[j].text == "だ" && wordInfos[j].partOfSpeech == PartOfSpeech.Auxiliary) {
                    combined += wordInfos[j].text
                    lastEndOffset = wordInfos[j].endOffset
                    j++
                }

                if (j < wordInfos.size && wordInfos[j].text == "と" && wordInfos[j].partOfSpeech == PartOfSpeech.Particle) {
                    combined += wordInfos[j].text
                    lastEndOffset = wordInfos[j].endOffset
                    j++
                }

                w1.text = combined
                w1.endOffset = lastEndOffset
                w1.dictionaryForm = combined
                w1.partOfSpeech = PartOfSpeech.Auxiliary
                newList.add(w1)
                i = j
                continue
            }

            // な|し after a na-adjective is なし; otherwise noun+な merges and orphans し.
            val followedByShi = i + 1 < wordInfos.size &&
                wordInfos[i + 1].let { it.text == "し" && it.partOfSpeech == PartOfSpeech.Conjunction }
            if (followedByShi && newList.size > 0 && isNaAdjectiveToken(newList[newList.size - 1])) {
                newList.add(WordInfo().apply {
                    text = "なし"; dictionaryForm = "なし"; normalizedForm = "無し"
                    partOfSpeech = PartOfSpeech.Noun; reading = "ナシ"
                    startOffset = w1.startOffset; endOffset = wordInfos[i + 1].endOffset
                })
                i += 2
                continue
            }

            // 大切|な → 大切な; な stays separate after an aux stem (降りそうな) for learners.
            if (newList.size > 0 && isNaAdjectiveToken(newList[newList.size - 1]) && !followedByN &&
                !newList[newList.size - 1].hasPartOfSpeechSection(PartOfSpeechSection.AuxiliaryVerbStem)) {
                newList[newList.size - 1].text += w1.text
                newList[newList.size - 1].endOffset = w1.endOffset
                i++
                continue
            }

            // Not the vegetable 菜.
            w1.partOfSpeech = PartOfSpeech.Particle
        }
        // Not the baggage 荷.
        else if (w1.text == "に") {
            w1.partOfSpeech = PartOfSpeech.Particle
        }

        newList.add(w1)
        i++
    }

    return newList
}

/** Sudachi fuses noun+verb stem into one noun, orphaning the ending (足蹴|られた → 足|蹴られた, 肉食|う → 肉|食う). */
internal fun MorphologicalAnalyser.repairOrphanedAuxiliary(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    val lookup = hasCompoundLookup
    if (wordInfos.size < 2 || lookup == null) return wordInfos

    val result = ArrayList<WordInfo>(wordInfos.size + 2)
    var changed = false

    for (i in wordInfos.indices) {
        val word = wordInfos[i]

        if (i == 0) {
            result.add(word)
            continue
        }

        // 連用形 noun + た/て is the whole verb (言い出し|た → 言い出した), not the split below; だ/で is copula (手伝いだ).
        val renyoNoun = result[result.size - 1]
        if (word.partOfSpeech == PartOfSpeech.Auxiliary && (word.text == "た" || word.text == "て") &&
            renyoNoun.partOfSpeech == PartOfSpeech.Noun &&
            renyoNoun.text.length >= 2 && renyoNoun.dictionaryForm == renyoNoun.text) {
            val dictEnd = when (renyoNoun.text.last()) {
                'し' -> 'す'; 'り' -> 'る'; 'き' -> 'く'; 'ぎ' -> 'ぐ'; 'み' -> 'む'
                'び' -> 'ぶ'; 'に' -> 'ぬ'; 'ち' -> 'つ'; 'い' -> 'う'; else -> '\u0000'
            }
            if (dictEnd != '\u0000') {
                val verbDict = renyoNoun.text.dropLast(1) + dictEnd
                if (verbDict.any { it in '一'..'龯' } && lookup(verbDict)) {
                    result[result.size - 1] = renyoNoun.copy().apply {
                        text = renyoNoun.text + word.text
                        dictionaryForm = verbDict; normalizedForm = verbDict
                        partOfSpeech = PartOfSpeech.Verb
                        endOffset = word.endOffset
                    }
                    changed = true
                    continue
                }
            }
        }

        // そりゃそうだ eats だろう's だ, stranding ろう as 蝋; reattach keeping the expression's dict form.
        val prevExpr = result[result.size - 1]
        if (word.text == "ろう" && word.partOfSpeech == PartOfSpeech.Noun &&
            prevExpr.partOfSpeech == PartOfSpeech.Expression &&
            prevExpr.text.endsWith('だ')) {
            result[result.size - 1] = prevExpr.copy().apply {
                text = prevExpr.text + "ろう"
                endOffset = word.endOffset
            }
            changed = true
            continue
        }

        val isOrphanedAuxiliary = word.partOfSpeech == PartOfSpeech.Auxiliary &&
            word.dictionaryForm in VerbIndicatingAuxiliaries
        val isOrphanedVerbEnding = !isOrphanedAuxiliary &&
            word.text.length == 1 &&
            word.text in GodanVerbEndings

        if (!isOrphanedAuxiliary && !isOrphanedVerbEnding) {
            result.add(word)
            continue
        }

        val prev = result[result.size - 1]
        if (prev.partOfSpeech != PartOfSpeech.Noun || prev.text.length < 2) {
            result.add(word)
            continue
        }

        val maxWindow = minOf(prev.text.length - 1, 3)
        var repaired = false

        var w = 1
        while (w <= maxWindow && !repaired) {
            val verbStem = prev.text.takeLast(w)

            if (!verbStem.any { it in '一'..'龯' }) { w++; continue }

            if (isOrphanedVerbEnding) {
                val dictForm = verbStem + word.text
                val nounRemainder = prev.text.dropLast(w)
                if (lookup(dictForm) && lookup(nounRemainder)) {
                    applyNounVerbSplit(prev, word, nounRemainder, verbStem, dictForm, result)
                    repaired = true
                }
            } else {
                for (ending in GodanVerbEndings) {
                    val dictForm = verbStem + ending
                    val nounRemainder = prev.text.dropLast(w)
                    if (lookup(dictForm) && lookup(nounRemainder)) {
                        applyNounVerbSplit(prev, word, nounRemainder, verbStem, dictForm, result)
                        repaired = true
                        break
                    }
                }
            }
            w++
        }

        if (repaired) changed = true

        if (!repaired) result.add(word)
    }

    return if (changed) result else wordInfos
}

private fun applyNounVerbSplit(
    noun: WordInfo, orphan: WordInfo, nounRemainder: String, verbStem: String, dictForm: String, result: MutableList<WordInfo>,
) {
    val w = noun.text.length - nounRemainder.length
    val origNounEnd = noun.endOffset
    noun.text = nounRemainder
    noun.endOffset = if (noun.startOffset >= 0) noun.startOffset + nounRemainder.length else -1
    if (noun.dictionaryForm.endsWith(verbStem)) noun.dictionaryForm = noun.dictionaryForm.dropLast(w)
    if (noun.normalizedForm.endsWith(verbStem)) noun.normalizedForm = noun.normalizedForm.dropLast(w)

    result.add(WordInfo().apply {
        text = verbStem + orphan.text
        this.dictionaryForm = dictForm
        normalizedForm = dictForm
        partOfSpeech = PartOfSpeech.Verb
        startOffset = if (origNounEnd >= 0) origNounEnd - w else -1
        endOffset = orphan.endOffset
    })
}

@Suppress("UnusedReceiverParameter")
internal fun MorphologicalAnalyser.repairHasaNoun(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.isEmpty()) return wordInfos

    val result = ArrayList<WordInfo>(wordInfos.size + 2)

    for (word in wordInfos) {
        if (word.text != "はさ" || word.partOfSpeech != PartOfSpeech.Noun) {
            result.add(word)
            continue
        }

        result.add(WordInfo().apply {
            text = "は"
            dictionaryForm = "は"
            normalizedForm = "は"
            partOfSpeech = PartOfSpeech.Particle
            startOffset = word.startOffset
            endOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
        })
        result.add(WordInfo().apply {
            text = "さ"
            dictionaryForm = "さ"
            normalizedForm = "さ"
            partOfSpeech = PartOfSpeech.Particle
            startOffset = if (word.startOffset >= 0) word.startOffset + 1 else -1
            endOffset = word.endOffset
        })
    }

    return result
}

private val SentenceFinalParticles: Set<String> = linkedSetOf("ね", "ねえ", "な", "なあ", "よ", "よね", "さ", "わ", "の", "のよ", "もの")

/** Splits an unmatched interjection that absorbed a sentence-final particle (ごめんなさいね). */
internal fun MorphologicalAnalyser.repairFusedInterjectionParticle(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.isEmpty()) return wordInfos

    var result: MutableList<WordInfo>? = null

    for (i in wordInfos.indices) {
        val word = wordInfos[i]
        if (word.partOfSpeech != PartOfSpeech.Interjection || word.text.length < 3) {
            result?.add(word)
            continue
        }

        val lookup = hasCompoundLookup
        if (lookup != null && lookup(word.text)) {
            result?.add(word)
            continue
        }

        var split = false
        for (particle in SentenceFinalParticles.sortedByDescending { it.length }) {
            if (!word.text.endsWith(particle)) continue

            val baseText = word.text.dropLast(particle.length)
            if (baseText.length < 2) continue

            if (lookup != null && !lookup(baseText)) continue

            val list = result ?: copyAccumulatorUpTo(wordInfos, i).also { result = it }
            list.add(WordInfo().apply {
                text = baseText
                dictionaryForm = baseText
                normalizedForm = baseText
                partOfSpeech = PartOfSpeech.Interjection
                startOffset = word.startOffset
                endOffset = if (word.startOffset >= 0) word.startOffset + baseText.length else -1
            })
            list.add(WordInfo().apply {
                text = particle
                dictionaryForm = particle
                normalizedForm = particle
                partOfSpeech = PartOfSpeech.Particle
                startOffset = if (word.startOffset >= 0) word.startOffset + baseText.length else -1
                endOffset = word.endOffset
            })
            split = true
            break
        }

        if (!split) result?.add(word)
    }

    return result ?: wordInfos
}

/** A trailing run of ≥2 identical small vowels (ぇぇぇ) is expressive elongation, not part of a word. Returns coreLen, or null for false. */
private fun isTrailingSmallVowelRun(text: String): Int? {
    val smallVowels = "ぁぃぅぇぉ"
    var i = text.length - 1
    var runChar = '\u0000'
    var runCount = 0
    while (i >= 0 && smallVowels.indexOf(text[i]) >= 0 && (runCount == 0 || text[i] == runChar)) {
        runChar = text[i]
        runCount++
        i--
    }
    val coreLen = i + 1
    return if (runCount >= 2 && coreLen >= 1) coreLen else null
}

private val CaseParticles: Set<String> =
    linkedSetOf("に", "を", "が", "へ", "で", "と", "は", "も", "か", "から", "より", "まで", "の")

private val CommonTeFormVerbs: Set<String> =
    linkedSetOf("なる", "する", "やる", "いる", "ある", "くる", "できる", "おる", "みる", "しまう", "よる")

// Te-form いって: the い reattaches only when that attests (悪|いっ|て → 悪い; ギシギシい does not).
private val IuIkuDictForms: Set<String> = linkedSetOf("いう", "言う", "いく", "行く")

// Sudachi prefers the exclamation homograph (それっ!) before って; re-tagged Pronoun so lookup picks the everyday word.
private val QuotativeStrippedPronouns: Set<String> = linkedSetOf("それ", "これ", "あれ", "どれ")

// Nouns and に/と are excluded so どうにかなって/夢かなって keep their なる/かなう te-form reading.
private fun isClauseFinalBeforeKana(w: WordInfo): Boolean =
    (w.partOfSpeech == PartOfSpeech.Particle && w.text == "の") ||
        w.partOfSpeech == PartOfSpeech.IAdjective ||
        (w.partOfSpeech == PartOfSpeech.Verb && w.text == w.dictionaryForm) ||
        (w.partOfSpeech == PartOfSpeech.Auxiliary && w.text in setOf("ない", "た", "だ", "です", "ます", "てる", "でる"))

// One-step imperative/volitional only, so 信じろ+って matches and a genuine te-form (信じきって) never does.
private fun MorphologicalAnalyser.mergesToFinalForm(text: String): Boolean = oneStepFinalFormBase(text, includeVolitional = true) != null

private fun MorphologicalAnalyser.oneStepFinalFormBase(text: String, includeVolitional: Boolean): String? {
    val lookup = hasCompoundLookup ?: return null
    for (f in Deconjugator.instance.deconjugate(text)) {
        if (f.process.size != 1 || f.text.isEmpty()) continue
        val p = f.process[0]
        if (("imperative" in p || (includeVolitional && "volitional" in p)) && lookup(f.text))
            return f.text
    }

    return null
}

private fun hasOneStepVolitional(text: String): Boolean {
    for (f in Deconjugator.instance.deconjugate(text))
        if (f.process.size == 1 && "volitional" in f.process[0]) return true

    return false
}

// ってな(=という) takes a noun head (ってな具合), never bare に, so ってな|に and って|な|に are って + なに.
@Suppress("UnusedReceiverParameter")
internal fun MorphologicalAnalyser.repairTteNani(words: MutableList<WordInfo>): MutableList<WordInfo> =
    scanRewrite(words) { tokens, i, _, output ->
        fun nani(basis: WordInfo, startOffset: Int, endOffset: Int): WordInfo = basis.copy().apply {
            text = "なに"; dictionaryForm = "なに"; normalizedForm = "何"; reading = "ナニ"
            partOfSpeech = PartOfSpeech.Noun; partOfSpeechSection1 = PartOfSpeechSection.None
            preMatchedWordId = 1577100
            this.startOffset = startOffset; this.endOffset = endOffset
        }

        if (tokens[i].text == "ってな" && i + 1 < tokens.size && tokens[i + 1].text == "に") {
            val result = output()
            val tteNa = tokens[i]
            val ni = tokens[i + 1]
            val mid = if (tteNa.startOffset >= 0) tteNa.startOffset + 2 else -1
            result.add(tteNa.copy().apply {
                text = "って"; dictionaryForm = "って"; normalizedForm = "って"; reading = "ッテ"
                partOfSpeech = PartOfSpeech.Particle; endOffset = mid
            })
            result.add(nani(ni, mid, ni.endOffset))
            return@scanRewrite 2
        }

        if (tokens[i].text == "って" && i + 2 < tokens.size &&
            tokens[i + 1].text == "な" && tokens[i + 2].text == "に") {
            val result = output()
            val na = tokens[i + 1]
            val ni = tokens[i + 2]
            result.add(tokens[i])
            result.add(nani(na, na.startOffset, ni.endOffset))
            return@scanRewrite 3
        }

        0
    }

/** C# line 11 (first half). Verb heads a quotative って attaches to (言う/思う/聞く/考える/感じる). */
private val QuoteVerbHeads_p2: CharArray = charArrayOf('言', '思', '聞', '考', '感')

/** C# lines 351-352 (first half). */
private const val RareVerbShredMinRank_p2 = 5000
private const val RareVerbShredRankRatio_p2 = 4

/** Jiten.Parser/Scoring/AdjacentWordScorer.cs IsNumericSurface (private copy until Scoring is ported). */
private fun isNumericSurfaceScorer(text: String?): Boolean {
    if (text.isNullOrEmpty()) return false
    var i = if (text[0] == '第' || text[0] == '何' || text[0] == '数') 1 else 0
    if (i == text.length) return text[0] == '何' || text[0] == '数'
    while (i < text.length) {
        if (!JapaneseTextHelper.isNumeralChar(text[i])) return false
        i++
    }
    return true
}

private fun isHiraganaChar(c: Char) = c in 'ぁ'..'ゖ'
private fun isKanjiChar(c: Char) = c in '一'..'鿿'

internal fun MorphologicalAnalyser.repairQuotativeTte(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    if (wordInfos.size < 2) return wordInfos

    val deconj = Deconjugator.instance
    val result = ArrayList<WordInfo>(wordInfos.size + 2)
    var changed = false

    fun last(k: Int = 1) = result[result.size - k]

    fun addTte(thiefToken: WordInfo, teToken: WordInfo) {
        result.add(teToken.copy().apply {
            text = "って"
            dictionaryForm = "って"
            normalizedForm = "って"
            partOfSpeech = PartOfSpeech.Particle
            startOffset = if (thiefToken.endOffset >= 0) thiefToken.endOffset - 1 else -1
            endOffset = teToken.endOffset
        })
    }

    // って steals a noun's tail mora (寒|さって); returns the LONGEST attested run (繋|が|り → 繋がり, not がり), 0 if none.
    fun wordReattachRunLength(add: String): Int {
        val lookup = hasNonNameCompoundLookup
        if (lookup == null || add.isEmpty()) return 0
        var acc = add
        var best = 0
        var k = 1
        while (k <= 3 && k <= result.size) {
            val rk = last(k)
            // Stops at auxiliaries (ませ+ん), verbs (生き|て|い|こうっ|て) and が/と/を/に/か (と+いって attests 問い).
            // っ-final and bare す "verbs" are shreds (いっ|しょっ|て, びびり|す|ぎっ); the lookup gate still applies.
            if ((rk.partOfSpeech == PartOfSpeech.Verb && !rk.text.endsWith('っ') && rk.text != "す") ||
                rk.partOfSpeech == PartOfSpeech.Auxiliary ||
                (rk.partOfSpeech == PartOfSpeech.Particle && rk.text in setOf("が", "と", "を", "に", "か"))) break
            // Topic は stops too, except clause-initially where it can only be a word head (は|ずっ|て = はず).
            if (rk.partOfSpeech == PartOfSpeech.Particle && rk.text == "は") {
                val clauseInitial = result.size <= k ||
                    last(k + 1).partOfSpeech.let {
                        it == PartOfSpeech.Symbol || it == PartOfSpeech.SupplementarySymbol || it == PartOfSpeech.BlankSpace
                    }
                if (!clauseInitial) break
            }
            val t = rk.text
            if (t.isEmpty()) break
            // で heads only the shred です (で|すっ|て); anything else is two real tokens (で+すく=デスク).
            if (rk.partOfSpeech == PartOfSpeech.Particle && rk.text == "で" && t + acc != "です") break
            var kanaOrKanji = true
            for (c in t)
                if (!(c in 'ぁ'..'ゖ' || c in '゠'..'ヿ' || c in '一'..'鿿')) { kanaOrKanji = false; break }
            if (!kanaOrKanji) break
            acc = t + acc
            if (lookup(acc)) {
                // All-word kana pieces (どう+こう) are two words meeting; っ-final pieces (いっ) count as shreds regardless.
                var allPiecesAreWords = acc.length >= 4 && acc.all(::isHiraganaChar)
                if (allPiecesAreWords) {
                    var m = 1
                    while (m <= k && allPiecesAreWords) {
                        allPiecesAreWords = !last(m).text.endsWith('っ') && lookup(last(m).text)
                        m++
                    }
                }
                if (!allPiecesAreWords) best = k
            }
            k++
        }
        // A stranded lone kana before the run would be deleted by FilterOrphanedMisparses; range must match its set.
        if (best > 0 && result.size > best) {
            val lead = last(best + 1)
            if (lead.text.length == 1 &&
                (lead.text[0] in 'ぁ'..'ゟ' || lead.text[0] in '゠'..'ヿ') &&
                (lead.partOfSpeech == PartOfSpeech.Noun || lead.partOfSpeech == PartOfSpeech.Symbol))
                return 0
        }
        return best
    }

    fun mergeRunInto(runLen: Int, add: String, mergedEnd: Int) {
        val n = result.size
        val basis = result[n - runLen]
        var mergedText = ""
        var mergedReading = ""
        for (x in n - runLen until n) { mergedText += result[x].text; mergedReading += result[x].reading }
        mergedText += add
        mergedReading += WanaKana.toKatakana(add)
        result.subList(n - runLen, n).clear()
        result.add(basis.copy().apply {
            text = mergedText; dictionaryForm = mergedText; normalizedForm = mergedText; reading = mergedReading
            partOfSpeech = PartOfSpeech.Noun
            partOfSpeechSection1 = PartOfSpeechSection.None
            partOfSpeechSection2 = PartOfSpeechSection.None
            partOfSpeechSection3 = PartOfSpeechSection.None
            endOffset = mergedEnd
        })
    }

    // The う-row check rejects fragments the deconjugator over-tags as verb pasts (はだ, んだ).
    fun isVerbReattachment(s: String): Boolean =
        (s.length >= 2 &&
            s.last() in "うくぐすつぬぶむる" &&
            hasNonNameCompoundLookup?.invoke(s) == true &&
            deconjugatesToVerb(s)) ||
            mergesToFinalForm(s)

    fun isNominal(p: PartOfSpeech) = p == PartOfSpeech.Noun || p == PartOfSpeech.CommonNoun ||
        p == PartOfSpeech.Pronoun || p == PartOfSpeech.Name || p == PartOfSpeech.Suffix

    var i = -1
    while (true) {
        i++
        if (i >= wordInfos.size) break

        // Nominal + だって|ば is copula だ + emphatic ってば (ダルマザメだってば), not the conjunction だって.
        if (i + 1 < wordInfos.size &&
            wordInfos[i].text == "だって" &&
            (wordInfos[i].partOfSpeech == PartOfSpeech.Conjunction || wordInfos[i].partOfSpeech == PartOfSpeech.Particle) &&
            wordInfos[i + 1].text == "ば" &&
            result.size > 0 &&
            isNominal(last().partOfSpeech)) {
            val datte = wordInfos[i]
            val mid = if (datte.startOffset >= 0) datte.startOffset + 1 else -1
            result.add(datte.copy().apply {
                text = "だ"; dictionaryForm = "だ"; normalizedForm = "だ"; reading = "ダ"
                partOfSpeech = PartOfSpeech.Auxiliary; endOffset = mid
            })
            result.add(datte.copy().apply {
                text = "ってば"; dictionaryForm = "ってば"; normalizedForm = "ってば"; reading = "ッテバ"
                partOfSpeech = PartOfSpeech.Particle; preMatchedWordId = 2130420
                startOffset = mid; endOffset = wordInfos[i + 1].endOffset
            })
            i++
            changed = true
            continue
        }

        // 将校|だっ(だつ)|て is particle だって; SplitTatteParticle already ran, and the mora-theft block would fake 将校だ.
        if (i + 1 < wordInfos.size &&
            wordInfos[i].text == "だっ" && wordInfos[i].dictionaryForm == "だつ" &&
            wordInfos[i + 1].text == "て" &&
            result.size > 0 &&
            isNominal(last().partOfSpeech)) {
            val dattsu = wordInfos[i]
            result.add(dattsu.copy().apply {
                text = "だって"; dictionaryForm = "だって"; normalizedForm = "だって"; reading = "ダッテ"
                partOfSpeech = PartOfSpeech.Particle
                partOfSpeechSection1 = PartOfSpeechSection.ConjunctionParticle
                endOffset = wordInfos[i + 1].endOffset
            })
            i++
            changed = true
            continue
        }

        // Adverb いたって before 言う after a て-form or particle (部署に|いたって言う) is いた + quotative って.
        if (result.size > 0 &&
            wordInfos[i].text == "いたって" && wordInfos[i].partOfSpeech == PartOfSpeech.Adverb &&
            i + 1 < wordInfos.size &&
            (wordInfos[i + 1].dictionaryForm == "いう" || wordInfos[i + 1].dictionaryForm == "言う") &&
            (last().text.endsWith("て") ||
                last().text.endsWith("で") ||
                (last().partOfSpeech == PartOfSpeech.Particle && last().text in setOf("が", "に", "は", "も")))) {
            val fused = wordInfos[i]
            val mid = if (fused.startOffset >= 0) fused.startOffset + 2 else -1
            result.add(fused.copy().apply {
                text = "いた"; dictionaryForm = "いる"; normalizedForm = "いる"; reading = "イタ"
                partOfSpeech = PartOfSpeech.Verb; endOffset = mid
            })
            result.add(fused.copy().apply {
                text = "って"; dictionaryForm = "って"; normalizedForm = "って"; reading = "ッテ"
                partOfSpeech = PartOfSpeech.Particle; startOffset = mid
            })
            changed = true
            continue
        }

        // って steals a katakana noun's tail (サナダ|ムシっ|て, エリ|アっての); the lookup gate spares サボってる.
        val nonNameLookup = hasNonNameCompoundLookup
        if (result.size > 0 && JapaneseTextHelper.isAllKatakana(last().text) && nonNameLookup != null) {
            val cur = wordInfos[i].text
            var kl = 0
            while (kl < cur.length && JapaneseTextHelper.isKatakanaWordChar(cur[kl])) kl++

            if (kl >= 1 && kl < cur.length && cur[kl] == 'っ' &&
                nonNameLookup(last().text + cur.substring(0, kl))) {
                val prevKata = last()
                val kataWord = prevKata.text + cur.substring(0, kl)
                val rest = cur.substring(kl)
                val kEnd = if (wordInfos[i].startOffset >= 0) wordInfos[i].startOffset + kl else -1

                if (rest == "っ" && i + 1 < wordInfos.size && wordInfos[i + 1].text == "て") {
                    result[result.size - 1] = prevKata.copy().apply {
                        text = kataWord; dictionaryForm = kataWord; normalizedForm = kataWord
                        partOfSpeech = PartOfSpeech.Noun; endOffset = kEnd
                    }
                    addTte(wordInfos[i], wordInfos[i + 1])
                    i++
                    changed = true
                    continue
                }

                if (rest.startsWith("って")) {
                    result[result.size - 1] = prevKata.copy().apply {
                        text = kataWord; dictionaryForm = kataWord; normalizedForm = kataWord
                        partOfSpeech = PartOfSpeech.Noun; endOffset = kEnd
                    }
                    result.add(wordInfos[i].copy().apply {
                        text = "って"; dictionaryForm = "って"; normalizedForm = "って"
                        partOfSpeech = PartOfSpeech.Particle
                        startOffset = kEnd
                        endOffset = if (kEnd >= 0) kEnd + 2 else -1
                    })
                    val tail = rest.substring(2)
                    if (tail.isNotEmpty())
                        result.addAll(tokenizeGrammarRemainder(tail, if (kEnd >= 0) kEnd + 2 else -1))
                    changed = true
                    continue
                }
            }
        }

        // それっ|て → それ|って, vouched by Sudachi's NormalizedForm; else CombineTte builds a bogus exclamation te-form.
        if (i + 1 < wordInfos.size &&
            wordInfos[i].partOfSpeech == PartOfSpeech.Interjection &&
            wordInfos[i].text.length >= 2 &&
            wordInfos[i].text.last() == 'っ' &&
            wordInfos[i + 1].text == "て" &&
            wordInfos[i].normalizedForm == wordInfos[i].text.dropLast(1)) {
            val interjThief = wordInfos[i]
            val interjStripped = interjThief.text.dropLast(1)
            result.add(interjThief.copy().apply {
                text = interjStripped
                dictionaryForm = interjStripped
                normalizedForm = interjStripped
                partOfSpeech = if (interjStripped in QuotativeStrippedPronouns) PartOfSpeech.Pronoun else interjThief.partOfSpeech
                endOffset = if (interjThief.endOffset >= 0) interjThief.endOffset - 1 else -1
            })
            addTte(interjThief, wordInfos[i + 1])
            i++
            changed = true
            continue
        }

        // がる never follows a pronoun, so 何|がっ|て is 何|が|って.
        if (i + 1 < wordInfos.size &&
            wordInfos[i].let { it.text == "がっ" && it.partOfSpeech == PartOfSpeech.Suffix && it.dictionaryForm == "がる" } &&
            wordInfos[i + 1].text == "て" &&
            result.size > 0 && last().partOfSpeech == PartOfSpeech.Pronoun) {
            val gaThief = wordInfos[i]
            result.add(WordInfo().apply {
                text = "が"
                dictionaryForm = "が"
                normalizedForm = "が"
                partOfSpeech = PartOfSpeech.Particle
                reading = "ガ"
                startOffset = gaThief.startOffset
                endOffset = if (gaThief.startOffset >= 0) gaThief.startOffset + 1 else -1
            })
            addTte(gaThief, wordInfos[i + 1])
            i++
            changed = true
            continue
        }

        // 読|むっ|て: the thief's POS is noise; the signal is the mora rebuilding a verb. あっ|て rebuilds none.
        if (i + 1 < wordInfos.size &&
            wordInfos[i].text.length == 2 &&
            wordInfos[i].text.last() == 'っ' &&
            isHiraganaChar(wordInfos[i].text[0]) &&
            wordInfos[i].text[0] != 'だ' &&
            wordInfos[i + 1].text == "て" &&
            !(wordInfos[i].partOfSpeech == PartOfSpeech.Particle || wordInfos[i].partOfSpeech == PartOfSpeech.Pronoun) &&
            result.size > 0) {
            val thiefMora = wordInfos[i].text[0]

            var stemBack = 0
            if (result.size >= 2 && last().partOfSpeech == PartOfSpeech.Suffix &&
                isVerbReattachment(last(2).text + last().text + thiefMora))
                stemBack = 2
            else if (last().partOfSpeech.let {
                    it == PartOfSpeech.Noun || it == PartOfSpeech.CommonNoun || it == PartOfSpeech.Verb ||
                        it == PartOfSpeech.Prefix || it == PartOfSpeech.Suffix
                } && isVerbReattachment(last().text + thiefMora))
                stemBack = 1

            if (stemBack > 0) {
                val moraThief = wordInfos[i]
                val stemHead = result[result.size - stemBack]
                var verbText = ""
                for (k in result.size - stemBack until result.size) verbText += result[k].text
                verbText += thiefMora
                // An imperative Sudachi agrees on keeps its verb lemma (来い → 来る, not the interjection); 待ち合わせ is no 待ち合わす imperative.
                var imperativeBase = oneStepFinalFormBase(verbText, includeVolitional = false)
                if (imperativeBase != null &&
                    KanaConverter.toHiragana(imperativeBase) != KanaConverter.toHiragana(stemHead.dictionaryForm))
                    imperativeBase = null
                result.subList(result.size - stemBack, result.size).clear()
                result.add(stemHead.copy().apply {
                    text = verbText
                    dictionaryForm = imperativeBase ?: verbText
                    normalizedForm = imperativeBase ?: verbText
                    partOfSpeech = PartOfSpeech.Verb
                    isImperative = imperativeBase != null
                    endOffset = if (moraThief.startOffset >= 0) moraThief.startOffset + 1 else -1
                })
                addTte(moraThief, wordInfos[i + 1])
                i++
                changed = true
                continue
            }
        }

        // Non-verb reattachment ありがと|うっ|て → ありがとう; only interjection うっ (verb うつ reforms above).
        if (i + 1 < wordInfos.size &&
            wordInfos[i].text == "うっ" && wordInfos[i].dictionaryForm == "うっ" &&
            wordInfos[i + 1].text == "て" &&
            result.size > 0 &&
            last().partOfSpeech.let {
                it == PartOfSpeech.Interjection || it == PartOfSpeech.Noun || it == PartOfSpeech.CommonNoun || it == PartOfSpeech.Filler
            } &&
            isHiraganaChar(last().text.last()) &&
            hasNonNameCompoundLookup?.invoke(last().text + "う") == true) {
            val moraThief = wordInfos[i]
            val reattached = last().text + "う"
            result[result.size - 1] = last().copy().apply {
                text = reattached; dictionaryForm = reattached; normalizedForm = reattached
                endOffset = if (moraThief.startOffset >= 0) moraThief.startOffset + 1 else -1
            }
            addTte(moraThief, wordInfos[i + 1])
            i++
            changed = true
            continue
        }

        // A noun's final kanji takes って's っ as a verb (自|意|識っ[識る]|て, 若|造っ|て), blocking compound matching.
        // Adj/verb predecessors are safe only as bare kanji; a genuine 若い/識って carries okurigana.
        if (i + 1 < wordInfos.size &&
            wordInfos[i].partOfSpeech == PartOfSpeech.Verb &&
            wordInfos[i].text.length == 2 &&
            wordInfos[i].text.last() == 'っ' &&
            isKanjiChar(wordInfos[i].text[0]) &&
            wordInfos[i + 1].text == "て" &&
            result.size > 0 &&
            last().partOfSpeech.let {
                it == PartOfSpeech.Noun || it == PartOfSpeech.CommonNoun || it == PartOfSpeech.Suffix ||
                    it == PartOfSpeech.Prefix || it == PartOfSpeech.IAdjective || it == PartOfSpeech.Verb
            } &&
            isKanjiChar(last().text.last())) {
            val kanjiThief = wordInfos[i]
            val tailKanji = kanjiThief.text.dropLast(1)
            val formsNoun =
                hasNonNameCompoundLookup?.invoke(last().text + tailKanji) == true ||
                    (result.size >= 2 &&
                        last(2).partOfSpeech.let {
                            it == PartOfSpeech.Noun || it == PartOfSpeech.CommonNoun ||
                                it == PartOfSpeech.Suffix || it == PartOfSpeech.Prefix
                        } &&
                        isKanjiChar(last(2).text.last()) &&
                        hasNonNameCompoundLookup?.invoke(last(2).text + last().text + tailKanji) == true)

            if (formsNoun) {
                result.add(kanjiThief.copy().apply {
                    text = tailKanji
                    dictionaryForm = tailKanji
                    normalizedForm = tailKanji
                    partOfSpeech = PartOfSpeech.Noun
                    endOffset = if (kanjiThief.startOffset >= 0) kanjiThief.startOffset + 1 else -1
                })
                addTte(kanjiThief, wordInfos[i + 1])
                i++
                changed = true
                continue
            }
        }

        // Kanji noun read as a far rarer verb (番っ[番う]|て, 主っ[司る]|て) is noun + って/っす; 言う, 打つ outrank their nouns.
        val rankLookup = getNonNameCompoundFrequencyRank
        val nounRank = if (i + 1 < wordInfos.size &&
            wordInfos[i].partOfSpeech == PartOfSpeech.Verb &&
            wordInfos[i].text.length == 2 &&
            (wordInfos[i].text.last() == 'っ' || wordInfos[i].text.last() == 'ッ') &&
            isKanjiChar(wordInfos[i].text[0]) &&
            wordInfos[i + 1].text in setOf("て", "す", "ス")) rankLookup?.invoke(wordInfos[i].text.substring(0, 1)) else null
        val verbRank = if (nounRank != null) rankLookup!!(wordInfos[i].dictionaryForm) else null
        if (nounRank != null && verbRank != null &&
            verbRank >= RareVerbShredMinRank_p2 &&
            verbRank >= nounRank * RareVerbShredRankRatio_p2) {
            val shred = wordInfos[i]
            val next = wordInfos[i + 1]
            val noun = shred.text.substring(0, 1)
            result.add(shred.copy().apply {
                text = noun; dictionaryForm = noun; normalizedForm = noun
                reading = ""
                partOfSpeech = PartOfSpeech.Noun
                partOfSpeechSection1 = PartOfSpeechSection.None
                endOffset = if (shred.startOffset >= 0) shred.startOffset + 1 else -1
            })
            if (next.text == "て") {
                addTte(shred, next)
            } else {
                val tail = shred.text.substring(1) + next.text
                result.add(next.copy().apply {
                    text = tail; dictionaryForm = "です"; normalizedForm = "です"
                    reading = WanaKana.toKatakana(tail)
                    partOfSpeech = PartOfSpeech.Auxiliary
                    startOffset = if (shred.endOffset >= 0) shred.endOffset - 1 else -1
                })
            }
            i++
            changed = true
            continue
        }

        // A noun can't take past た, so 貴方|た|ちっ|て is 貴方|たち|って.
        if (i + 1 < wordInfos.size &&
            wordInfos[i].let { it.text == "ちっ" && it.partOfSpeech == PartOfSpeech.Auxiliary && it.dictionaryForm == "ちう" } &&
            wordInfos[i + 1].text == "て" &&
            result.size >= 2 &&
            last().text == "た" && last().partOfSpeech == PartOfSpeech.Auxiliary &&
            last(2).partOfSpeech.let { it == PartOfSpeech.Pronoun || it == PartOfSpeech.Noun || it == PartOfSpeech.CommonNoun }) {
            val chiThief = wordInfos[i]
            result[result.size - 1] = last().copy().apply {
                text = "たち"
                dictionaryForm = "たち"
                normalizedForm = "達"
                reading = "タチ"
                partOfSpeech = PartOfSpeech.Suffix
                endOffset = if (chiThief.startOffset >= 0) chiThief.startOffset + 1 else -1
            }
            addTte(chiThief, wordInfos[i + 1])
            i++
            changed = true
            continue
        }

        // 婆|さ|んっ|て|ね: the gate below filters Interjection, so ん reattaches here.
        if (i + 1 < wordInfos.size &&
            wordInfos[i].text == "んっ" && wordInfos[i].partOfSpeech == PartOfSpeech.Interjection &&
            wordInfos[i + 1].text == "て" &&
            result.size > 0) {
            val nRun = wordReattachRunLength("ん")
            if (nRun > 0) {
                mergeRunInto(nRun, "ん", if (wordInfos[i].endOffset >= 0) wordInfos[i].endOffset - 1 else -1)
                addTte(wordInfos[i], wordInfos[i + 1])
                i++
                changed = true
                continue
            }
        }

        // っつー steals morae like って (びびり|す|ぎっ|つーか); only the noun run applies, never a te-form.
        if (wordInfos[i].text.length > 1 && wordInfos[i].text.last() == 'っ' &&
            i + 1 < wordInfos.size &&
            (wordInfos[i + 1].text.startsWith("つー") ||
                wordInfos[i + 1].text.startsWith("つう") ||
                wordInfos[i + 1].text.startsWith("ちゅう")) &&
            result.size > 0) {
            val strippedMora = wordInfos[i].text.dropLast(1)
            if (strippedMora.all { isHiraganaChar(it) || isKanjiChar(it) }) {
                val tsuRun = wordReattachRunLength(strippedMora)
                if (tsuRun > 0) {
                    mergeRunInto(tsuRun, strippedMora, if (wordInfos[i].endOffset >= 0) wordInfos[i].endOffset - 1 else -1)
                    val contraction = wordInfos[i + 1]
                    result.add(contraction.copy().apply {
                        text = "っ" + contraction.text
                        reading = "ッ" + contraction.reading
                        startOffset = if (wordInfos[i].endOffset >= 0) wordInfos[i].endOffset - 1 else -1
                    })
                    i++
                    changed = true
                    continue
                }
            }
        }

        // Fused theft: 勉|強って (強って lemmatised as たって).
        if (wordInfos[i].text.length >= 3 &&
            wordInfos[i].text.endsWith("って") &&
            wordInfos[i].text.dropLast(2).all { JapaneseTextHelper.isKanji(it) } &&
            result.size > 0) {
            val head = wordInfos[i].text.dropLast(2)
            val headRun = wordReattachRunLength(head)
            if (headRun > 0) {
                mergeRunInto(headRun, head, if (wordInfos[i].startOffset >= 0) wordInfos[i].startOffset + head.length else -1)
                result.add(wordInfos[i].copy().apply {
                    text = "って"; dictionaryForm = "って"; normalizedForm = "って"; reading = "ッテ"
                    partOfSpeech = PartOfSpeech.Particle
                    startOffset = if (wordInfos[i].startOffset >= 0) wordInfos[i].startOffset + head.length else -1
                })
                changed = true
                continue
            }
        }

        // Auxiliary/Interjection thieves are lemmatised stolen morae too (育|ちっ|て, た|めっ|て).
        if (i + 1 >= wordInfos.size ||
            wordInfos[i].text.length < 2 ||
            wordInfos[i].text.last() != 'っ' ||
            wordInfos[i + 1].text != "て" ||
            wordInfos[i].partOfSpeech !in ThiefPos) {
            result.add(wordInfos[i])
            continue
        }

        val thief = wordInfos[i]
        val stripped = thief.text.dropLast(1)
        val prev = if (result.size > 0) last() else null

        // のか|なっ|て → のかな|って; a clause-final token before か makes the question reading certain.
        if (thief.text == "なっ" && prev != null && prev.partOfSpeech == PartOfSpeech.Particle && prev.text == "か" &&
            result.size >= 2 && isClauseFinalBeforeKana(last(2))) {
            result[result.size - 1] = prev.copy().apply {
                text = "かな"
                dictionaryForm = "かな"
                normalizedForm = "かな"
                endOffset = if (thief.endOffset >= 0) thief.endOffset - 1 else -1
            }
            addTte(thief, wordInfos[i + 1])
            i++
            changed = true
            continue
        }

        // 言うな|って: a terminal form never takes なる (that is たくなって), so な is sentence-final.
        if (thief.text == "なっ" && prev != null &&
            (((prev.partOfSpeech == PartOfSpeech.Verb || prev.partOfSpeech == PartOfSpeech.IAdjective) && prev.text == prev.dictionaryForm) ||
                (prev.partOfSpeech == PartOfSpeech.Auxiliary && prev.text in setOf("だ", "た", "です", "ます", "たい", "ない")))) {
            result.add(WordInfo().apply {
                text = "な"
                dictionaryForm = "な"
                normalizedForm = "な"
                partOfSpeech = PartOfSpeech.Particle
                startOffset = thief.startOffset
                endOffset = if (thief.startOffset >= 0) thief.startOffset + 1 else -1
            })
            addTte(thief, wordInfos[i + 1])
            i++
            changed = true
            continue
        }

        // 言|うなっ|て → 言う|な|って; genuine うなる "growl" follows a particle, which the prev-POS check excludes.
        if (thief.text == "うなっ" && prev != null &&
            prev.partOfSpeech !in UnaPrevExcluded &&
            (hasCompoundLookup?.invoke(prev.text + "う") == true || hasOneStepVolitional(prev.text + "う"))) {
            result[result.size - 1] = prev.copy().apply {
                text = prev.text + "う"
                endOffset = if (prev.endOffset >= 0) prev.endOffset + 1 else -1
            }
            result.add(WordInfo().apply {
                text = "な"
                dictionaryForm = "な"
                normalizedForm = "な"
                partOfSpeech = PartOfSpeech.Particle
                startOffset = if (thief.startOffset >= 0) thief.startOffset + 1 else -1
                endOffset = if (thief.startOffset >= 0) thief.startOffset + 2 else -1
            })
            addTte(thief, wordInfos[i + 1])
            i++
            changed = true
            continue
        }

        // デー|トっ|て: a mid-katakana cut before って is never real, so shape alone suffices (OOV names too).
        if (stripped.length == 1 && stripped[0] != 'ー' && JapaneseTextHelper.isKatakanaWordChar(stripped[0]) &&
            prev != null && JapaneseTextHelper.isAllKatakana(prev.text)) {
            val mergedKatakana = prev.text + stripped
            result[result.size - 1] = prev.copy().apply {
                text = mergedKatakana
                dictionaryForm = mergedKatakana
                normalizedForm = mergedKatakana
                partOfSpeech = PartOfSpeech.Noun
                endOffset = if (thief.endOffset >= 0) thief.endOffset - 1 else -1
            }
            addTte(thief, wordInfos[i + 1])
            i++
            changed = true
            continue
        }

        // すご|くっ[来る]|て is すごく|って; must run before the CommonTeFormVerbs skip, which would keep くる.
        if (stripped == "く" && thief.dictionaryForm == "くる" && prev != null &&
            (((prev.partOfSpeech == PartOfSpeech.IAdjective || prev.partOfSpeech == PartOfSpeech.Prefix) &&
                prev.dictionaryForm.endsWith("い") &&
                hasNonNameCompoundLookup?.invoke(prev.dictionaryForm) == true) ||
                // Bare-kanji adverb stem (早[早い]); all-kanji keeps kana お|くっ|て (送る) out.
                (prev.partOfSpeech == PartOfSpeech.Adverb &&
                    prev.text.isNotEmpty() && prev.text.all(::isKanjiChar) &&
                    hasNonNameCompoundLookup?.invoke(prev.text + "い") == true))) {
            result[result.size - 1] = prev.copy().apply {
                text = prev.text + "く"
                partOfSpeech = PartOfSpeech.IAdjective
                endOffset = if (thief.endOffset >= 0) thief.endOffset - 1 else -1
            }
            addTte(thief, wordInfos[i + 1])
            i++
            changed = true
            continue
        }

        // と|かっ[買う]|て is とか|って; a genuine 買って follows を or a noun, never と.
        if (stripped == "か" &&
            thief.partOfSpeech == PartOfSpeech.Verb &&
            thief.dictionaryForm in setOf("買う", "飼う", "かう") &&
            prev != null && prev.text == "と" && prev.partOfSpeech == PartOfSpeech.Particle) {
            result.add(thief.copy().apply {
                text = "か"; dictionaryForm = "か"; normalizedForm = "か"; reading = "カ"
                partOfSpeech = PartOfSpeech.Particle
                endOffset = if (thief.endOffset >= 0) thief.endOffset - 1 else -1
            })
            addTte(thief, wordInfos[i + 1])
            i++
            changed = true
            continue
        }

        // する has no すっ stem, so a すっ thief is always a shred (で|すっ|て = です+って).
        if (thief.partOfSpeech == PartOfSpeech.Verb && thief.dictionaryForm in CommonTeFormVerbs &&
            !(thief.dictionaryForm == "する" && thief.text == "すっ")) {
            result.add(wordInfos[i])
            continue
        }

        // 繋|がりっ|て → 繋がり|って; bare う is a volitional (いこ|うっ|て) and いっ/だっ would hit particles (て+い=弟).
        if (stripped.length >= 1 && stripped != "う" &&
            thief.dictionaryForm !in IuIkuDictForms &&
            thief.dictionaryForm != "だつ" &&
            stripped.all(::isHiraganaChar)) {
            val runLen = wordReattachRunLength(stripped)
            if (runLen > 0) {
                mergeRunInto(runLen, stripped, if (thief.endOffset >= 0) thief.endOffset - 1 else -1)
                addTte(thief, wordInfos[i + 1])
                i++
                changed = true
                continue
            }
        }

        // 必|要っ[要る]|て → 必要|って; the lookup gate keeps 家に帰って out (に+帰 does not attest).
        if (stripped.any(::isKanjiChar)) {
            val kanjiRun = wordReattachRunLength(stripped)
            if (kanjiRun > 0) {
                mergeRunInto(kanjiRun, stripped, if (thief.endOffset >= 0) thief.endOffset - 1 else -1)
                addTte(thief, wordInfos[i + 1])
                i++
                changed = true
                continue
            }

            // １０|度っ|て → 度|って; needs a counter sense, since 何|言っ|て is a genuine te-form.
            if (stripped.length == 1 && prev != null &&
                isNumericSurfaceScorer(prev.text) &&
                hasCounterSenseLookup?.invoke(stripped) == true) {
                result.add(thief.copy().apply {
                    text = stripped; dictionaryForm = stripped; normalizedForm = stripped
                    reading = if (thief.reading.length > 1) thief.reading.dropLast(1) else thief.reading
                    partOfSpeech = PartOfSpeech.Noun
                    endOffset = if (thief.endOffset >= 0) thief.endOffset - 1 else -1
                })
                addTte(thief, wordInfos[i + 1])
                i++
                changed = true
                continue
            }
        }

        // Later heuristics would split genuine auxiliary te-forms (食べ|ちゃっ|て), so these repair only via reattach.
        if (thief.partOfSpeech == PartOfSpeech.Auxiliary || thief.partOfSpeech == PartOfSpeech.Interjection ||
            thief.partOfSpeech == PartOfSpeech.Particle) {
            result.add(wordInfos[i])
            continue
        }

        var shouldRepair = false
        var mergeIntoPrev = false
        var mergeAsVerb = false

        if (stripped.length == 1 && isHiraganaChar(stripped[0]) && thief.partOfSpeech != PartOfSpeech.Adverb) {
            if (prev != null) {
                if (prev.partOfSpeech == PartOfSpeech.Particle && prev.text in CaseParticles) {
                    result.add(wordInfos[i])
                    continue
                }

                val merged = prev.text + stripped
                val forms = deconj.deconjugate(merged)
                // RunBfs always returns the identity form (0 steps), so exactly one step is required.
                var hasRealDeconjStep = false
                for (f in forms) {
                    if (f.process.size == 1) { hasRealDeconjStep = true; break }
                }
                // いっ|て is usually 言って/行って; steal い only for a real word (悪い, not ギシギシい).
                if (hasRealDeconjStep && thief.dictionaryForm in IuIkuDictForms &&
                    hasNonNameCompoundLookup?.invoke(merged) != true)
                    hasRealDeconjStep = false
                // 信じ|きっ|て is 信じきる's te-form; leave it for CombineCompounds, not a fake 信じき + って.
                if (hasRealDeconjStep && thief.partOfSpeech == PartOfSpeech.Verb &&
                    hasCompoundLookup?.invoke(prev.text + thief.dictionaryForm) == true)
                    hasRealDeconjStep = false
                if (hasRealDeconjStep) {
                    shouldRepair = true
                    mergeIntoPrev = true
                }
            }
        } else if (stripped.length >= 2) {
            var allKana = true
            for (c in stripped)
                if (!(c in 'ぁ'..'ゖ' || c in '゠'..'ヿ')) { allKana = false; break }

            if (allKana && thief.dictionaryForm !in CommonTeFormVerbs) {
                // Prefer a clause-final reattachment: 信|じろっ|て → 信じろ|って.
                if (prev != null &&
                    (prev.partOfSpeech == PartOfSpeech.Verb || prev.partOfSpeech == PartOfSpeech.Noun ||
                        prev.partOfSpeech == PartOfSpeech.CommonNoun) &&
                    mergesToFinalForm(prev.text + stripped)) {
                    shouldRepair = true
                    mergeIntoPrev = true
                    mergeAsVerb = true
                } else if (thief.partOfSpeech != PartOfSpeech.Adverb) {
                    // A genuine te-stem (わかっ+て → わかる) is no theft, unless a quote verb follows (かなっ|て|思ったら).
                    // The frame is deliberately broad: わかって思った loses its boundary, but narrowing costs more.
                    val quoteVerbFollows = i + 2 < wordInfos.size &&
                        wordInfos[i + 2].partOfSpeech == PartOfSpeech.Verb &&
                        wordInfos[i + 2].text.isNotEmpty() &&
                        wordInfos[i + 2].text[0] in QuoteVerbHeads_p2

                    var selfTeForm = false
                    if (!quoteVerbFollows &&
                        thief.partOfSpeech == PartOfSpeech.Verb && thief.dictionaryForm.length >= 2 &&
                        thief.dictionaryForm != thief.text &&
                        hasNonNameCompoundLookup?.invoke(thief.dictionaryForm) == true) {
                        val dictHira = KanaConverter.toHiragana(thief.dictionaryForm)
                        for (f in deconj.deconjugate(KanaConverter.toHiragana(thief.text) + "て")) {
                            if (f.text == dictHira) { selfTeForm = true; break }
                        }
                    }

                    if (!selfTeForm) {
                        val forms = deconj.deconjugate(stripped)
                        shouldRepair = forms.isNotEmpty()
                    }
                }
                // Adverb-tagged するっ|て is する|って; the う-row gate keeps ことっ a noun.
                else if (isVerbReattachment(stripped)) {
                    shouldRepair = true
                }
            }
        }

        if (shouldRepair) {
            if (mergeIntoPrev && prev != null) {
                result[result.size - 1] = prev.copy().apply {
                    text = prev.text + stripped
                    partOfSpeech = if (mergeAsVerb) PartOfSpeech.Verb else prev.partOfSpeech
                    endOffset = if (thief.endOffset >= 0) thief.endOffset - 1 else -1
                }
            }
            // やれ|やれっ|て → やれやれ|って; otherwise the copy is dropped as a kana stutter.
            else if (prev != null && prev.text == stripped &&
                (hasNonNameCompoundLookup ?: hasCompoundLookup)?.invoke(prev.text + stripped) == true) {
                result[result.size - 1] = prev.copy().apply {
                    text = prev.text + stripped
                    dictionaryForm = prev.text + stripped
                    normalizedForm = prev.text + stripped
                    endOffset = if (thief.endOffset >= 0) thief.endOffset - 1 else -1
                }
            } else {
                result.add(thief.copy().apply {
                    text = stripped; partOfSpeech = PartOfSpeech.Verb
                    endOffset = if (thief.endOffset >= 0) thief.endOffset - 1 else -1
                })
            }

            addTte(thief, wordInfos[i + 1])
            i++
            changed = true
            continue
        }

        result.add(wordInfos[i])
    }

    return if (changed) result else wordInfos
}

private val ThiefPos: Set<PartOfSpeech> = setOf(
    PartOfSpeech.Verb, PartOfSpeech.Noun, PartOfSpeech.CommonNoun, PartOfSpeech.Adverb, PartOfSpeech.Auxiliary,
    PartOfSpeech.Interjection, PartOfSpeech.Particle,
)

private val UnaPrevExcluded: Set<PartOfSpeech> = setOf(
    PartOfSpeech.Particle, PartOfSpeech.Auxiliary, PartOfSpeech.SupplementarySymbol, PartOfSpeech.Symbol, PartOfSpeech.BlankSpace,
)

// Clipped exclamatory adjective (足短っ, 寒っ); unrepaired, 短 resolves to the noun "fault; defect".
internal fun MorphologicalAnalyser.repairClippedAdjective(words: MutableList<WordInfo>): MutableList<WordInfo> {
    if (hasNonNameCompoundLookup == null || hasVerbOrAdjectiveLookup == null) return words
    return scanRewrite(words) { tokens, i, result, output -> tryRepairClippedAdjective(tokens, i, result, output) }
}

private fun MorphologicalAnalyser.tryRepairClippedAdjective(
    tokens: MutableList<WordInfo>, i: Int, @Suppress("UNUSED_PARAMETER") unused: MutableList<WordInfo>?,
    output: () -> MutableList<WordInfo>,
): Int {
    val current = tokens[i]

    var tsuIdx = -1
    var j = i + 1
    while (j < tokens.size && j <= i + 2) {
        // The forced sokuon boundary leaves a blank or stop token between stem and っ.
        if (tokens[j].partOfSpeech == PartOfSpeech.BlankSpace || tokens[j].text == StopToken) { j++; continue }
        if (tokens[j].text == "っ" || tokens[j].text == "ッ") tsuIdx = j
        break
    }

    if (tsuIdx < 0 ||
        current.text.isEmpty() || current.text.length > 4 ||
        current.preMatchedWordId != null ||
        current.partOfSpeech in ClippedAdjectiveExcludedPos ||
        // A kana stem + い hits unrelated keys (がん+い = 含意) and resurrects SFX fragments.
        !JapaneseTextHelper.isKanji(current.text.last()) ||
        // Verb/adjective entries only, so noun collisions (兄い) don't qualify.
        !hasVerbOrAdjectiveLookup!!(current.text + "い") ||
        // Vocative 隊|長っ must re-fuse to 隊長, not become 長い.
        (i > 0 && hasNonNameCompoundLookup!!(tokens[i - 1].text + current.text)))
        return 0

    output().add(current.copy().apply {
        text = current.text + "っ"
        dictionaryForm = current.text + "い"
        normalizedForm = current.text + "い"
        reading = ""
        partOfSpeech = PartOfSpeech.IAdjective
        endOffset = tokens[tsuIdx].endOffset
    })
    return tsuIdx - i + 1
}

private val ClippedAdjectiveExcludedPos: Set<PartOfSpeech> = setOf(
    PartOfSpeech.Verb, PartOfSpeech.Particle, PartOfSpeech.Auxiliary, PartOfSpeech.Symbol, PartOfSpeech.SupplementarySymbol,
)

/** Classical attributive き fused into the next noun (白|き尾 → 白き|尾). */
internal fun MorphologicalAnalyser.repairClassicalKiAdjective(words: MutableList<WordInfo>): MutableList<WordInfo> {
    if (hasNonNameCompoundLookup == null || hasCompoundLookup == null) return words
    return scanRewrite(words) { tokens, i, result, output -> tryRepairClassicalKiAdjective(tokens, i, result, output) }
}

private fun MorphologicalAnalyser.tryRepairClassicalKiAdjective(
    tokens: MutableList<WordInfo>, i: Int, result: MutableList<WordInfo>?, output: () -> MutableList<WordInfo>,
): Int {
    val current = tokens[i]
    // prev may already be rewritten this pass, so read the accumulator.
    val prev = if (i > 0) (if (result != null) result[result.size - 1] else tokens[i - 1]) else null

    if (prev == null ||
        current.partOfSpeech != PartOfSpeech.Noun ||
        current.text.length < 2 || current.text[0] != 'き' ||
        !(prev.partOfSpeech == PartOfSpeech.Noun || prev.partOfSpeech == PartOfSpeech.NaAdjective) ||
        prev.text.length < 1 || prev.text.length > 2 ||
        !prev.text.all { it in '一'..'鿿' } ||
        hasCompoundLookup!!(current.text) ||
        !hasNonNameCompoundLookup!!(prev.text + "い") ||
        !hasNonNameCompoundLookup!!(current.text.substring(1)))
        return 0

    val list = output()
    list[list.size - 1] = prev.copy().apply {
        text = prev.text + 'き'
        dictionaryForm = prev.text + "い"
        normalizedForm = prev.text + "い"
        partOfSpeech = PartOfSpeech.IAdjective
        endOffset = if (prev.endOffset >= 0) prev.endOffset + 1 else -1
    }
    list.add(current.copy().apply {
        text = current.text.substring(1)
        dictionaryForm = current.text.substring(1)
        normalizedForm = current.text.substring(1)
        startOffset = if (current.startOffset >= 0) current.startOffset + 1 else -1
    })
    return 1
}

internal fun MorphologicalAnalyser.recombineHiraganaTokens(words: MutableList<WordInfo>): MutableList<WordInfo> {
    val wordInfos = words
    val compoundLookup = hasCompoundLookup
    if (wordInfos.size < 2 || compoundLookup == null) return wordInfos

    val hasNonNameLookup = hasNonNameCompoundLookup ?: compoundLookup
    val hasPrioritizedLookup = hasPrioritizedNonNameCompoundLookup ?: hasNonNameLookup
    // A hiragana merge must hit a kana-plausible word, else reading keys collide (はい+そう → 配送).
    val hasKanaAppropriateLookup = hasKanaAppropriateCompoundLookup ?: hasNonNameLookup

    val deconjugator = Deconjugator.instance
    var result: MutableList<WordInfo>? = null

    var i = 0
    while (i < wordInfos.size) {
        var combined = false
        val maxSpan = minOf(4, wordInfos.size - i)

        var spanLen = maxSpan
        while (spanLen >= 2 && !combined) {
            var allValid = true
            var totalLen = 0

            for (j in i until i + spanLen) {
                val w = wordInfos[j]
                if (!JapaneseTextHelper.isAllHiragana(w.text) ||
                    PosMapper.isInflectableBase(w.partOfSpeech) ||
                    w.partOfSpeech in RecombineExcludedPos ||
                    (w.partOfSpeech == PartOfSpeech.Expression && (w.text == "だった" || w.text == "だったら"))) {
                    allValid = false
                    break
                }

                totalLen += w.text.length
            }

            if (!allValid || totalLen < 3 || totalLen > 12) { spanLen--; continue }

            var allSameInterjection = wordInfos[i].partOfSpeech == PartOfSpeech.Interjection
            if (allSameInterjection) {
                val firstText = wordInfos[i].text
                for (j in i + 1 until i + spanLen) {
                    if (wordInfos[j].text != firstText || wordInfos[j].partOfSpeech != PartOfSpeech.Interjection) {
                        allSameInterjection = false
                        break
                    }
                }
            }
            if (allSameInterjection) { spanLen--; continue }

            // えっ|そう is two utterances; neither えっそう nor its 得る deconjugation is valid.
            if (wordInfos[i].partOfSpeech == PartOfSpeech.Interjection && wordInfos[i + 1].text == "そう") { spanLen--; continue }

            val combinedText = buildString { for (j in i until i + spanLen) append(wordInfos[j].text) }

            if (hasKanaAppropriateLookup(combinedText)) {
                val list = result ?: copyAccumulatorUpTo(wordInfos, i).also { result = it }
                list.add(buildMergedHiraganaToken(wordInfos, i, spanLen, combinedText, combinedText, PartOfSpeech.CommonNoun))
                i += spanLen
                combined = true
                break
            }

            var allTokensPrioritized = true
            for (j in i until i + spanLen) {
                if (!hasPrioritizedLookup(wordInfos[j].text)) {
                    allTokensPrioritized = false
                    break
                }
            }

            if (allTokensPrioritized) { spanLen--; continue }

            val hiragana = KanaConverter.toNormalizedHiragana(combinedText)
            val forms = deconjugator.deconjugate(hiragana)

            for (form in forms) {
                if (form.tags.isEmpty() || form.tags.size > 5) continue
                val lastTag = form.tags[form.tags.size - 1]
                if (!lastTag.startsWith('v') && lastTag != "adj-i" && lastTag != "adj-na") continue
                if (!hasNonNameLookup(form.text)) continue

                val pos = when (lastTag) {
                    "adj-i" -> PartOfSpeech.IAdjective
                    "adj-na" -> PartOfSpeech.NaAdjective
                    else -> PartOfSpeech.Verb
                }

                val list = result ?: copyAccumulatorUpTo(wordInfos, i).also { result = it }
                list.add(buildMergedHiraganaToken(wordInfos, i, spanLen, combinedText, form.text, pos))
                i += spanLen
                combined = true
                break
            }
            spanLen--
        }

        if (!combined) {
            result?.add(wordInfos[i])
            i++
        }
    }

    return result ?: wordInfos
}

private val RecombineExcludedPos: Set<PartOfSpeech> = setOf(
    PartOfSpeech.Particle, PartOfSpeech.Auxiliary, PartOfSpeech.Prefix, PartOfSpeech.Suffix, PartOfSpeech.NounSuffix,
    PartOfSpeech.SupplementarySymbol, PartOfSpeech.Symbol, PartOfSpeech.BlankSpace, PartOfSpeech.Conjunction,
)

private fun buildMergedHiraganaToken(
    tokens: List<WordInfo>, start: Int, count: Int, surface: String, dictForm: String, pos: PartOfSpeech,
): WordInfo {
    val first = tokens[start]
    val last = tokens[start + count - 1]

    val mergedReading = buildString { for (j in start until start + count) append(tokens[j].reading) }

    return WordInfo().apply {
        text = surface
        dictionaryForm = dictForm
        normalizedForm = dictForm
        partOfSpeech = pos
        startOffset = first.startOffset
        endOffset = last.endOffset
        reading = mergedReading
    }
}
