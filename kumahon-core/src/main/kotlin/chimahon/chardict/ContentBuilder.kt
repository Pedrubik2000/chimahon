package chimahon.chardict

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Yomitan structured-content card of a character and the term entry around it: the port of Bee's
 * `content_builder.rs` (MIT, see NOTICE.md). The ids in `data` ("name", "name-romaji", "portrait-container", "source",
 * "role-container", "role") are what kumapie's Hoshidicts reads, so they stay as they are.
 */
class ContentBuilder(private val settings: DictSettings) {
    companion object {
        private val OPT_IS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        private val RE_VNDB_SPOILER = Regex("\\[spoiler].*?\\[/spoiler]", OPT_IS)
        private val RE_ANILIST_SPOILER = Regex("~!.*?!~", RegexOption.DOT_MATCHES_ALL)
        private val RE_URL = Regex("\\[url=[^\\]]+]([^\\[]*)\\[/url]", RegexOption.IGNORE_CASE)
        private val RE_QUOTE = Regex("\\[quote](.*?)\\[/quote]", OPT_IS)
        private val RE_CODE = Regex("\\[code](.*?)\\[/code]", OPT_IS)
        private val RE_RAW = Regex("\\[raw](.*?)\\[/raw]", OPT_IS)
        private val RE_UNDERLINE = Regex("\\[u](.*?)\\[/u]", OPT_IS)
        private val RE_STRIKETHROUGH = Regex("\\[s](.*?)\\[/s]", OPT_IS)
        private val RE_BBCODE_INNER = Regex("\\[(b|i)]([^\\[]*?)\\[/(b|i)]", OPT_IS)
        private val RE_PLACEHOLDER = Regex("\u0000NODE(\\d+)\u0000")

        private val ROLE_COLORS = mapOf("main" to "#4CAF50", "primary" to "#2196F3", "side" to "#FF9800", "appears" to "#9E9E9E")
        private val ROLE_LABELS = mapOf("main" to "Protagonist", "primary" to "Main Character", "side" to "Side Character", "appears" to "Minor Role")
        private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        private val SEX_DISPLAY = mapOf("m" to "♂ Male", "f" to "♀ Female", "male" to "♂ Male", "female" to "♀ Female")

        private const val MAX_DISPLAY_HEIGHT = 100
        private const val FALLBACK_DISPLAY_WIDTH = 67

        /** Removes spoilers: VNDB [spoiler]..[/spoiler] and AniList ~!..!~. */
        fun stripSpoilers(text: String): String =
            RE_ANILIST_SPOILER.replace(RE_VNDB_SPOILER.replace(text, ""), "").trim()

        /** [url=..]x[/url], [quote], [code], [raw], [u], [s] down to their inner text. */
        fun parseVndbMarkup(text: String): String {
            var t = RE_URL.replace(text, "$1")
            t = RE_QUOTE.replace(t, "$1")
            t = RE_CODE.replace(t, "$1")
            t = RE_RAW.replace(t, "$1")
            t = RE_UNDERLINE.replace(t, "$1")
            return RE_STRIKETHROUGH.replace(t, "$1")
        }

        /** BBCode [b] and [i] into structured-content spans: a string when there are no tags, else an array of strings and spans. */
        fun parseBbcodeToStructured(text: String): Any {
            val nodes = ArrayList<JSONObject>()
            var working = text
            while (true) {
                val m = RE_BBCODE_INNER.find(working) ?: break
                val open = m.groupValues[1].lowercase()
                val close = m.groupValues[3].lowercase()
                if (open != close) { // mismatched: keep the content, drop the tags
                    working = working.substring(0, m.range.first) + m.groupValues[2] + working.substring(m.range.last + 1)
                    continue
                }
                val inner = resolvePlaceholders(m.groupValues[2], nodes)
                val node = JSONObject().put("tag", "span").put("content", inner)
                if (open == "b") node.put("style", JSONObject().put("fontWeight", "bold"))
                else node.put("style", JSONObject().put("fontStyle", "italic"))
                nodes.add(node)
                working = working.substring(0, m.range.first) + "\u0000NODE${nodes.size - 1}\u0000" + working.substring(m.range.last + 1)
            }
            return resolvePlaceholders(working, nodes)
        }

        private fun resolvePlaceholders(text: String, nodes: List<JSONObject>): Any {
            if (!RE_PLACEHOLDER.containsMatchIn(text)) return text
            val result = JSONArray()
            var last = 0
            for (m in RE_PLACEHOLDER.findAll(text)) {
                if (m.range.first > last) result.put(text.substring(last, m.range.first))
                val idx = m.groupValues[1].toInt()
                if (idx < nodes.size) result.put(nodes[idx])
                last = m.range.last + 1
            }
            if (last < text.length) result.put(text.substring(last))
            return if (result.length() == 1) result.get(0) else result
        }

        /** [month, day] as "September 1". */
        fun formatBirthday(birthday: List<Int>): String {
            if (birthday.size < 2) return ""
            return "${MONTHS.getOrNull(birthday[0] - 1) ?: "Unknown"} ${birthday[1]}"
        }

        private fun span(content: String, style: JSONObject? = null, id: String? = null): JSONObject =
            JSONObject().put("tag", "span").put("content", content).also { o ->
                style?.let { o.put("style", it) }
                id?.let { o.put("data", JSONObject().put("id", it)) }
            }

        private fun img(path: String, w: Int, h: Int): JSONObject = JSONObject().put("tag", "img").put("path", path)
            .put("width", w).put("height", h).put("sizeUnits", "px").put("collapsible", false).put("collapsed", false).put("background", false)

        /** The honorific note on top of a card: "さん - Generic polite suffix". */
        fun honorificBanner(suffixDisplay: String, suffixDescription: String): JSONObject = JSONObject()
            .put("tag", "div")
            .put("style", JSONObject().put("fontSize", "0.85em").put("color", "#4A90D9").put("borderColor", "#4A90D9")
                .put("borderStyle", "solid").put("borderWidth", "0 0 0 3px").put("paddingLeft", "6px").put("marginBottom", "6px"))
            .put("content", JSONArray().put(span(suffixDisplay, JSONObject().put("fontWeight", "bold"))).put(JSONObject().put("tag", "span").put("content", " — $suffixDescription")))
    }

    /** Sex, age, height, weight, blood type and birthday on one line. */
    fun formatStats(c: Character): String {
        val parts = ArrayList<String>()
        c.sex?.let { s -> SEX_DISPLAY[s.lowercase()]?.let(parts::add) }
        c.age?.let { parts.add("$it years") }
        c.height?.let { parts.add("${it}cm") }
        c.weight?.let { parts.add("${it}kg") }
        c.bloodType?.let { parts.add("Blood Type $it") }
        c.birthday?.let { b -> formatBirthday(b).takeIf { it.isNotEmpty() }?.let { parts.add("Birthday: $it") } }
        return parts.joinToString(" • ")
    }

    private fun traitItems(c: Character): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for ((traits, label) in listOf(c.personality to "Personality", c.roles to "Role", c.engagesIn to "Activities", c.subjectOf to "Subject of")) {
            val names = traits.filter { it.name.isNotEmpty() && (settings.showSpoilers || it.spoiler == 0) }.map { it.name }
            if (names.isNotEmpty()) out.add(JSONObject().put("tag", "li").put("content", "$label: ${names.joinToString(", ")}"))
        }
        return out
    }

    private fun roleBadge(role: String, withTopMargin: Boolean): JSONObject {
        val style = JSONObject().put("background", ROLE_COLORS[role] ?: "#9E9E9E").put("color", "white").put("padding", "2px 6px")
            .put("borderRadius", "3px").put("fontSize", "0.85em")
        if (withTopMargin) style.put("marginTop", "4px")
        return JSONObject().put("tag", "span").put("style", style).put("data", JSONObject().put("id", "role"))
            .put("content", ROLE_LABELS[role] ?: "Unknown")
    }

    private fun nameBlock(c: Character, content: JSONArray) {
        if (c.nameOriginal.isNotEmpty()) {
            content.put(JSONObject().put("tag", "div").put("data", JSONObject().put("id", "name"))
                .put("style", JSONObject().put("fontWeight", "bold").put("fontSize", "1.2em")).put("content", c.nameOriginal))
        }
        if (c.name.isNotEmpty()) {
            content.put(JSONObject().put("tag", "div").put("data", JSONObject().put("id", "name-romaji"))
                .put("style", JSONObject().put("fontStyle", "italic").put("color", "#666").put("marginBottom", "8px")).put("content", c.name))
        }
    }

    private fun portrait(imagePath: String?, dims: Pair<Int, Int>?, content: JSONArray) {
        if (!settings.showImage || imagePath == null) return
        val (w, h) = if (dims != null && dims.first > 0 && dims.second > 0) {
            ((dims.first * MAX_DISPLAY_HEIGHT + dims.second / 2) / dims.second) to MAX_DISPLAY_HEIGHT
        } else FALLBACK_DISPLAY_WIDTH to MAX_DISPLAY_HEIGHT
        content.put(JSONObject().put("tag", "div").put("data", JSONObject().put("id", "portrait-container")).put("content", img(imagePath, w, h)))
    }

    private fun tail(c: Character, seiyuuImagePath: String?, seiyuuDims: Pair<Int, Int>?, content: JSONArray) {
        if (settings.showDescription) {
            val desc = c.description
            if (desc != null && desc.isNotBlank()) {
                val shown = if (!settings.showSpoilers) stripSpoilers(desc) else desc
                if (shown.isNotEmpty()) {
                    content.put(JSONObject().put("tag", "details").put("content", JSONArray()
                        .put(JSONObject().put("tag", "summary").put("content", "Description"))
                        .put(JSONObject().put("tag", "div").put("style", JSONObject().put("fontSize", "0.9em").put("marginTop", "4px"))
                            .put("content", parseBbcodeToStructured(parseVndbMarkup(shown))))))
                }
            }
        }
        if (settings.showTraits) {
            val items = ArrayList<JSONObject>()
            formatStats(c).takeIf { it.isNotEmpty() }?.let {
                items.add(JSONObject().put("tag", "li").put("style", JSONObject().put("fontWeight", "bold")).put("content", it))
            }
            items.addAll(traitItems(c))
            if (items.isNotEmpty()) {
                content.put(JSONObject().put("tag", "details").put("content", JSONArray()
                    .put(JSONObject().put("tag", "summary").put("content", "Character Information"))
                    .put(JSONObject().put("tag", "ul").put("style", JSONObject().put("marginTop", "4px").put("paddingLeft", "20px"))
                        .put("content", JSONArray(items)))))
            }
        }
        if (settings.showSeiyuu) {
            val va = c.seiyuu
            if (!va.isNullOrEmpty()) {
                val inner = JSONArray()
                if (seiyuuImagePath != null) {
                    val (w, h) = if (seiyuuDims != null && seiyuuDims.first > 0 && seiyuuDims.second > 0) {
                        ((seiyuuDims.first * 60 + seiyuuDims.second / 2) / seiyuuDims.second) to 60
                    } else 40 to 60
                    inner.put(img(seiyuuImagePath, w, h))
                }
                inner.put(JSONObject().put("tag", "div").put("style", JSONObject().put("fontSize", "0.9em").put("marginTop", "4px")).put("content", va))
                content.put(JSONObject().put("tag", "details").put("content", JSONArray()
                    .put(JSONObject().put("tag", "summary").put("content", "Voiced by"))
                    .put(JSONObject().put("tag", "div").put("style", JSONObject().put("marginTop", "4px")).put("content", inner))))
            }
        }
    }

    /** The card of a character with one appearance: name, romaji, portrait, "From: <title>", role badge, description, traits, seiyuu. */
    fun buildContent(
        c: Character, imagePath: String?, imageDims: Pair<Int, Int>?,
        seiyuuImagePath: String?, seiyuuDims: Pair<Int, Int>?, gameTitle: String,
    ): JSONArray {
        val content = JSONArray()
        nameBlock(c, content)
        portrait(imagePath, imageDims, content)
        if (gameTitle.isNotEmpty()) {
            content.put(JSONObject().put("tag", "div").put("data", JSONObject().put("id", "source"))
                .put("style", JSONObject().put("fontSize", "0.9em").put("color", "#888").put("marginTop", "4px")).put("content", "From: $gameTitle"))
        }
        if (settings.showTag) {
            // a div around the span: tools that strip HTML (JL) and Yomitan's schema both want it
            content.put(JSONObject().put("tag", "div").put("data", JSONObject().put("id", "role-container")).put("content", roleBadge(c.role, true)))
        }
        tail(c, seiyuuImagePath, seiyuuDims, content)
        return content
    }

    /** The card of a character in several media: one (role badge, "From: <title>") row per appearance, best role first. */
    fun buildMergedContent(
        c: Character, imagePath: String?, imageDims: Pair<Int, Int>?,
        seiyuuImagePath: String?, seiyuuDims: Pair<Int, Int>?, appearances: List<Pair<String, String>>,
    ): JSONArray {
        val content = JSONArray()
        nameBlock(c, content)
        portrait(imagePath, imageDims, content)
        for ((role, title) in appearances) {
            if (settings.showTag) {
                val row = JSONArray().put(roleBadge(role, false))
                if (title.isNotEmpty()) {
                    row.put(span(" From: $title", JSONObject().put("fontSize", "0.9em").put("color", "#888"), "source"))
                }
                content.put(JSONObject().put("tag", "div").put("data", JSONObject().put("id", "role-container"))
                    .put("style", JSONObject().put("marginTop", "4px")).put("content", row))
            } else if (title.isNotEmpty()) {
                content.put(JSONObject().put("tag", "div").put("data", JSONObject().put("id", "source"))
                    .put("style", JSONObject().put("fontSize", "0.9em").put("color", "#888").put("marginTop", "4px")).put("content", "From: $title"))
            }
        }
        tail(c, seiyuuImagePath, seiyuuDims, content)
        return content
    }
}
