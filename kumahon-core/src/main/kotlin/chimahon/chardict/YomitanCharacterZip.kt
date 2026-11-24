package chimahon.chardict

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the characters as a Yomitan dictionary zip with the same structure as Bee's `dict_builder.rs` (MIT, see
 * NOTICE.md): index.json, tag_bank_1.json, term_bank_N.json (2000 entries each) and img/<file>. A character gets
 * entries for its name (and each half of it), the kana forms, its aliases, and every honorific variant of those
 * (さん, ちゃん, 先生, ...), all with the same structured-content card. kumapie's Hoshidicts reads the result.
 */
class YomitanCharacterZip(
    private val settings: DictSettings = DictSettings(),
    private val title: String = DEFAULT_TITLE,
    private val description: String = "",
    private val revision: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")),
) {
    companion object {
        /** kumahon's own dictionary title: distinct from kumapie's "Bee's Characters (books & manga)", and it still says "Characters" for isCharacter. */
        const val DEFAULT_TITLE = "kumahon Characters (books & manga)"
        const val TERM_BANK_LIMIT = 2_000

        private fun score(role: String) = when (role) { "main" -> 100; "primary" -> 75; "side" -> 50; "appears" -> 25; else -> 0 }
        private fun roleImportance(role: String) = when (role) { "main" -> 0; "primary" -> 1; "side" -> 2; "appears" -> 3; else -> 4 }

        /**
         * AniList alternative names often read "Romanized (日本語)": the Japanese part is the term and the romanized
         * part (as kana) its reading. Anything else is returned as it is, without a reading.
         */
        internal fun parseAltNameFormat(alias: String): Pair<String, String?> {
            val open = alias.lastIndexOf('(')
            if (open >= 0) {
                val close = alias.indexOf(')', open)
                if (close > open) {
                    val inside = alias.substring(open + 1, close).trim()
                    val outside = alias.substring(0, open).trim()
                    if (Kana.containsJapanese(inside) && outside.isNotEmpty()) {
                        return inside to Kana.alphabetToKana(outside).replace(" ", "")
                    }
                }
            }
            return alias to null
        }

        private val BANNERS: List<String> by lazy { HONORIFIC_SUFFIXES.map { (d, _, desc) -> ContentBuilder.honorificBanner(d, desc).toString() } }
    }

    /** A card's serialized items: the card itself, or with an honorific banner on top. */
    private class Card(val items: String) {
        fun plain() = "{\"type\":\"structured-content\",\"content\":[$items]}"
        fun honorific(banner: String) = "{\"type\":\"structured-content\",\"content\":[$banner${if (items.isEmpty()) "" else ",$items"}]}"
    }

    private class Entry(val term: String, val reading: String, val role: String, val score: Int, val card: Card) {
        fun tail(content: String) = "," + JSONObject.quote(reading) + "," + JSONObject.quote(if (role.isEmpty()) "name" else "name $role") +
            ",\"\",$score,[$content],0,\"\"]"
    }

    private class Merged(var character: Character, val appearances: MutableList<Pair<String, String>>, var bestScore: Int, var bestRole: String)

    private class HonorificSource(val bases: List<Pair<String, String>>, val card: Card, val role: String, val score: Int, val added: Set<String>)

    private val entries = ArrayList<Entry>()
    private val honorificSources = ArrayList<HonorificSource>()
    private val images = LinkedHashMap<String, ByteArray>()
    private val seenById = HashMap<Pair<String, String>, Int>()
    private val seenByName = HashMap<String, Int>()
    private val merged = ArrayList<Merged>()
    private var finalized = false
    private val skippedNoJapanese = HashMap<String, Int>()

    /** Characters without a Japanese name that were left out, per media title. */
    val skippedNoJapaneseSummary: Map<String, Int> get() = skippedNoJapanese.toSortedMap()

    val hasEntries: Boolean get() = entries.isNotEmpty() || honorificSources.isNotEmpty() || merged.isNotEmpty()

    /**
     * Collects a character for [write]. The same (source, id) in several media, or the same name (spaces ignored),
     * becomes one entry with one appearance row per media; two different characters with one name stay two.
     */
    fun addCharacter(c: Character, gameTitle: String) {
        check(!finalized) { "already written" }
        if (c.nameOriginal.isEmpty()) {
            skippedNoJapanese.merge(gameTitle, 1, Int::plus)
            return
        }
        val normalized = c.nameOriginal.filter { !it.isWhitespace() }
        val newScore = score(c.role)
        val appearance = c.role to gameTitle
        val idKey = c.source to c.id
        val sameId = seenById[idKey]
        if (sameId != null) {
            absorb(merged[sameId], c, appearance, newScore)
            return
        }
        val sameName = seenByName[normalized]
        if (sameName != null) {
            absorb(merged[sameName], c, appearance, newScore)
            if (c.source.isNotEmpty() && c.id.isNotEmpty()) seenById[idKey] = sameName
            return
        }
        val idx = merged.size
        merged.add(Merged(c, mutableListOf(appearance), newScore, c.role))
        if (c.source.isNotEmpty() && c.id.isNotEmpty()) seenById[idKey] = idx
        seenByName[normalized] = idx
    }

    private fun absorb(m: Merged, other: Character, appearance: Pair<String, String>, newScore: Int) {
        m.appearances.add(appearance)
        if (newScore > m.bestScore) { m.bestScore = newScore; m.bestRole = other.role }
        m.character = fillGaps(m.character, other)
    }

    /** First-non-null wins; aliases are the union. */
    private fun fillGaps(b: Character, o: Character): Character {
        fun union(x: List<String>, y: List<String>): List<String> {
            val seen = x.toMutableSet()
            return x + y.filter { it.isNotEmpty() && seen.add(it) }
        }
        return b.copy(
            sex = b.sex ?: o.sex, age = b.age ?: o.age, height = b.height ?: o.height, weight = b.weight ?: o.weight,
            bloodType = b.bloodType ?: o.bloodType, birthday = b.birthday ?: o.birthday, description = b.description ?: o.description,
            imageUrl = b.imageUrl ?: o.imageUrl, imageBytes = b.imageBytes ?: o.imageBytes, imageExt = b.imageExt ?: o.imageExt,
            imageWidth = b.imageWidth ?: o.imageWidth, imageHeight = b.imageHeight ?: o.imageHeight,
            seiyuu = b.seiyuu ?: o.seiyuu, seiyuuImageUrl = b.seiyuuImageUrl ?: o.seiyuuImageUrl,
            seiyuuImageBytes = b.seiyuuImageBytes ?: o.seiyuuImageBytes, seiyuuImageExt = b.seiyuuImageExt ?: o.seiyuuImageExt,
            seiyuuImageWidth = b.seiyuuImageWidth ?: o.seiyuuImageWidth, seiyuuImageHeight = b.seiyuuImageHeight ?: o.seiyuuImageHeight,
            firstNameHint = b.firstNameHint ?: o.firstNameHint, lastNameHint = b.lastNameHint ?: o.lastNameHint,
            name = if (b.name.isEmpty() && o.name.isNotEmpty()) o.name else b.name,
            personality = b.personality.ifEmpty { o.personality }, roles = b.roles.ifEmpty { o.roles },
            engagesIn = b.engagesIn.ifEmpty { o.engagesIn }, subjectOf = b.subjectOf.ifEmpty { o.subjectOf },
            aliases = union(b.aliases, o.aliases), spoilerAliases = union(b.spoilerAliases, o.spoilerAliases),
        )
    }

    private fun finalizeEntries() {
        if (finalized) return
        finalized = true
        for (m in merged) {
            m.appearances.sortBy { roleImportance(it.first) } // stable: main first
            generateEntries(m)
        }
        merged.clear()
    }

    private fun addImage(fileName: String, bytes: ByteArray): String {
        images.putIfAbsent(fileName, bytes)
        return "img/$fileName"
    }

    private fun generateEntries(m: Merged) {
        val c = m.character
        val name = c.nameOriginal
        val score = m.bestScore
        val tagRole = if (settings.showTag) m.bestRole else ""
        val cb = ContentBuilder(settings)
        val readings = NameParser.generateNameReadings(name, c.name, c.firstNameHint, c.lastNameHint)

        val imagePath = if (settings.showImage && c.imageBytes != null) {
            addImage(ImageSizes.fileName(c.id, c.imageExt ?: "jpg"), c.imageBytes)
        } else null
        val seiyuuPath = if (settings.showSeiyuu && c.seiyuuImageBytes != null) {
            addImage(ImageSizes.fileName("${c.id}_va", c.seiyuuImageExt ?: "jpg"), c.seiyuuImageBytes)
        } else null
        val dims = if (c.imageWidth != null && c.imageHeight != null) c.imageWidth to c.imageHeight else null
        val seiyuuDims = if (c.seiyuuImageWidth != null && c.seiyuuImageHeight != null) c.seiyuuImageWidth to c.seiyuuImageHeight else null

        val content = if (m.appearances.size > 1) {
            cb.buildMergedContent(c, imagePath, dims, seiyuuPath, seiyuuDims, m.appearances)
        } else {
            cb.buildContent(c, imagePath, dims, seiyuuPath, seiyuuDims, m.appearances.firstOrNull()?.second ?: "")
        }
        val card = Card(content.toString().let { it.substring(1, it.length - 1) })

        val added = HashSet<String>()
        fun add(term: String, reading: String) {
            entries.add(Entry(term, reading, tagRole, score, card))
        }
        fun addOnce(term: String, reading: String) {
            if (term.isNotEmpty() && added.add(term)) add(term, reading)
        }

        val parts = NameParser.splitJapaneseNameWithHints(name, c.firstNameHint, c.lastNameHint)
        val candidates = NameParser.splitJapaneseNameAllCandidates(name, c.firstNameHint, c.lastNameHint)
        val hasSplit = parts.hasSpace || parts.family != null

        if (hasSplit) {
            addOnce(parts.original, readings.full) // "須々木 心一"
            addOnce(parts.combined, readings.full) // "須々木心一"
            for (cand in candidates) {
                cand.family?.let { addOnce(it, readings.family) }
                cand.given?.let { addOnce(it, readings.given) }
            }
        } else {
            addOnce(name, readings.full)
        }

        // hiragana and katakana forms
        if (hasSplit) {
            val kataFamily = Kana.hiraToKata(readings.family)
            val kataGiven = Kana.hiraToKata(readings.given)
            addOnce(readings.family + readings.given, readings.full)
            // "すずき しんいち" is added without the empty check, as Bee does
            val hiraSpaced = "${readings.family} ${readings.given}"
            if (added.add(hiraSpaced)) add(hiraSpaced, readings.full)
            addOnce(readings.family, readings.family)
            addOnce(readings.given, readings.given)
            addOnce(kataFamily + kataGiven, readings.full)
            val kataSpaced = "$kataFamily $kataGiven"
            if (added.add(kataSpaced)) add(kataSpaced, readings.full)
            addOnce(kataFamily, readings.family)
            addOnce(kataGiven, readings.given)
        } else {
            addOnce(readings.full, readings.full)
            addOnce(Kana.hiraToKata(readings.full), readings.full)
        }

        // honorific bases: every written form of the name, with its reading
        val bases = ArrayList<Pair<String, String>>()
        if (hasSplit) {
            for (cand in candidates) {
                cand.family?.takeIf { it.isNotEmpty() }?.let { bases.add(it to readings.family) }
                cand.given?.takeIf { it.isNotEmpty() }?.let { bases.add(it to readings.given) }
            }
            if (parts.combined.isNotEmpty()) bases.add(parts.combined to readings.full)
            if (parts.original.isNotEmpty()) bases.add(parts.original to readings.full)
            if (readings.family.isNotEmpty()) bases.add(readings.family to readings.family)
            if (readings.given.isNotEmpty()) bases.add(readings.given to readings.given)
            val hiraCombined = readings.family + readings.given
            if (hiraCombined.isNotEmpty()) bases.add(hiraCombined to readings.full)
            val kataFamily = Kana.hiraToKata(readings.family)
            val kataGiven = Kana.hiraToKata(readings.given)
            if (kataFamily.isNotEmpty()) bases.add(kataFamily to readings.family)
            if (kataGiven.isNotEmpty()) bases.add(kataGiven to readings.given)
            val kataCombined = kataFamily + kataGiven
            if (kataCombined.isNotEmpty()) bases.add(kataCombined to readings.full)
        } else if (name.isNotEmpty()) {
            bases.add(name to readings.full)
            if (readings.full.isNotEmpty()) bases.add(readings.full to readings.full)
            val kataFull = Kana.hiraToKata(readings.full)
            if (kataFull.isNotEmpty()) bases.add(kataFull to readings.full)
        }

        // aliases ("Romanized (日本語)" or plain), with the spoiler ones when spoilers are on
        val spoilerAliases = if (settings.showSpoilers) c.spoilerAliases else emptyList()
        for (alias in c.aliases + spoilerAliases) {
            if (alias.isEmpty()) continue
            val (term, aliasReading) = parseAltNameFormat(alias)
            if (!Kana.containsJapanese(term)) continue
            if (added.add(term)) {
                val reading = aliasReading ?: readings.full
                add(term, reading)
                if (settings.honorifics) bases.add(term to reading)
            }
        }

        if (settings.honorifics && bases.isNotEmpty()) honorificSources.add(HonorificSource(bases, card, tagRole, score, added))
    }

    private fun indexJson(): String {
        val desc = description.ifEmpty { "Character names dictionary" }
        return JSONObject().put("title", title).put("revision", revision).put("format", 3)
            .put("author", "Bee (https://github.com/bee-san)").put("description", desc).toString(2)
    }

    private fun tagBank(): String {
        val tags = JSONArray().put(JSONArray().put("name").put("partOfSpeech").put(0).put("Character name").put(0))
        if (settings.showTag) {
            tags.put(JSONArray().put("main").put("name").put(0).put("Protagonist").put(0))
            tags.put(JSONArray().put("primary").put("name").put(0).put("Main character").put(0))
            tags.put(JSONArray().put("side").put("name").put(0).put("Side character").put(0))
            tags.put(JSONArray().put("appears").put("name").put(0).put("Minor appearance").put(0))
        }
        tags.put(JSONArray().put("dict-built").put("meta").put(0).put(LocalDate.now().toString()).put(0))
        return tags.toString()
    }

    /**
     * Writes the zip. Honorific entries are built one at a time and streamed into the term banks (a character has
     * dozens of names times ~270 honorifics: they are never all in memory). Entries whose term holds 妃 after at
     * least one character and whose first part is a consort's name (玉葉妃 where the dictionary has no 玉葉) are
     * also written without it (玉葉, 玉葉さん, ...) in a last bank: how kumapie's `reading_characters.py` `_bare` does it.
     */
    fun write(out: OutputStream) {
        finalizeEntries()
        val zip = ZipOutputStream(out)
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        fun text(name: String, body: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(body.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        text("index.json", indexJson())
        text("tag_bank_1.json", tagBank())

        val buffer = StringBuilder()
        var inBuffer = 0
        var bank = 1
        fun flush() {
            if (inBuffer == 0) return
            text("term_bank_${bank++}.json", "[$buffer]")
            buffer.setLength(0)
            inBuffer = 0
        }
        val baseTerms = HashSet<String>()
        val honorificTerms = HashSet<String>()
        // 妃 terms: the entry's parts (the card is shared, not copied); its text is built again only for a bare name
        class Kisaki(val e: Entry, val banner: Int)
        val kisaki = ArrayList<Kisaki>()
        fun emit(e: Entry, banner: Int) {
            val term = e.term
            val tail = e.tail(if (banner < 0) e.card.plain() else e.card.honorific(BANNERS[banner]))
            if (inBuffer > 0) buffer.append(',')
            buffer.append('[').append(JSONObject.quote(term)).append(tail)
            inBuffer++
            if (term.indexOf('妃') > 0) kisaki.add(Kisaki(e, banner))
            if (inBuffer >= TERM_BANK_LIMIT) flush()
        }
        for (e in entries) {
            baseTerms.add(e.term)
            emit(e, -1)
        }
        for (src in honorificSources) {
            for ((base, baseReading) in src.bases) {
                for ((i, h) in HONORIFIC_SUFFIXES.withIndex()) {
                    val term = base + h.first
                    if (term in src.added) continue
                    if (!honorificTerms.add(term)) continue
                    emit(Entry(term, baseReading + h.second, src.role, src.score, src.card), i)
                }
            }
        }
        // bare consort names
        fun known(t: String) = t in baseTerms || t in honorificTerms
        val consorts = HashSet<String>()
        for (terms in listOf(baseTerms, honorificTerms)) {
            for (t in terms) {
                if (t.endsWith("妃") && t.codePointCount(0, t.length) > 2) {
                    val stem = t.dropLast(1)
                    if (!known(stem)) consorts.add(stem)
                }
            }
        }
        val bare = ArrayList<Pair<String, Kisaki>>()
        if (consorts.isNotEmpty()) {
            for (k in kisaki) {
                val t = k.e.term
                val i = t.indexOf('妃')
                if (t.substring(0, i) in consorts) {
                    val w = t.substring(0, i) + t.substring(i + 1)
                    if (!known(w) && baseTerms.add(w)) bare.add(w to k)
                }
            }
        }
        for ((w, k) in bare) {
            if (inBuffer > 0) buffer.append(',')
            buffer.append('[').append(JSONObject.quote(w)).append(k.e.tail(if (k.banner < 0) k.e.card.plain() else k.e.card.honorific(BANNERS[k.banner])))
            inBuffer++
            if (inBuffer >= TERM_BANK_LIMIT) flush()
        }
        flush()

        for ((file, bytes) in images) { // already compressed: stored
            val e = ZipEntry("img/$file")
            e.method = ZipEntry.STORED
            e.size = bytes.size.toLong()
            e.compressedSize = bytes.size.toLong()
            e.crc = CRC32().apply { update(bytes) }.value
            zip.putNextEntry(e)
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    /** Writes [file] through a .tmp name, so a reader listing *.zip never sees half a file. */
    fun writeFile(file: File) {
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().buffered().use { write(it) }
            try {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete() // gone after a move; after a failure it must not stay
        }
    }
}
