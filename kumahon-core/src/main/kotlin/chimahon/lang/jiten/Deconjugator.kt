// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Deconjugator.cs
package chimahon.lang.jiten

import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Yomitan-style deconjugation: a breadth-first search over deconjugator.json's rules (resources/jiten, read through the
 * classloader so it works in JVM tests and on Android), with Jiten's two-generation result cache.
 */
class Deconjugator(maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES) {
    data class DeconjugationCacheStats(
        val hits: Long, val misses: Long, val stores: Long, val evictions: Long,
        val count: Int, val maxEntries: Int, val bfsCalls: Long,
    )

    private enum class RuleMode { Standard, OnlyFinal, NeverFinal, Context, Rewrite }

    private class RuleIndexEntry(val rule: DeconjugationRule, val virtualRule: DeconjugationVirtualRule, val mode: RuleMode)

    private val maxCacheEntries = maxOf(1, maxCacheEntries)
    @Volatile private var gen0 = ConcurrentHashMap<String, List<DeconjugationForm>>(4096)
    @Volatile private var gen1: ConcurrentHashMap<String, List<DeconjugationForm>>? = null
    private val gen0Count = AtomicInteger()
    private val rotating = AtomicBoolean()
    private val cacheHitCount = AtomicLong()
    private val cacheMissCount = AtomicLong()
    private val cacheStoreCount = AtomicLong()
    private val cacheEvictionCount = AtomicLong()
    private val bfsCallCount = AtomicLong()

    private val rules: Array<DeconjugationRule> = loadRules()
    private val conEndIndex = HashMap<String, Array<RuleIndexEntry>>()
    private val maxConEndLength: Int

    init {
        val temp = HashMap<String, MutableList<RuleIndexEntry>>()
        var maxConEnd = 0
        for (rule in rules) {
            val mode = when (rule.type) {
                "onlyfinalrule" -> RuleMode.OnlyFinal
                "neverfinalrule" -> RuleMode.NeverFinal
                "contextrule" -> RuleMode.Context
                "rewriterule" -> RuleMode.Rewrite
                else -> RuleMode.Standard
            }
            for (i in rule.decEnd.indices) {
                val vr = DeconjugationVirtualRule(
                    rule.decEnd[i],
                    rule.conEnd.getOrNull(i) ?: rule.conEnd[0],
                    rule.decTag?.let { it.getOrNull(i) ?: it[0] },
                    rule.conTag?.let { it.getOrNull(i) ?: it[0] },
                    rule.detail,
                )
                maxConEnd = maxOf(maxConEnd, vr.conEnd.length)
                temp.getOrPut(vr.conEnd) { ArrayList() }.add(RuleIndexEntry(rule, vr, mode))
            }
        }
        for ((key, list) in temp) conEndIndex[key] = list.toTypedArray()
        maxConEndLength = maxConEnd
    }

    private fun tryGetCached(text: String): List<DeconjugationForm>? {
        gen0[text]?.let { cacheHitCount.incrementAndGet(); return it }
        gen1?.get(text)?.let {
            gen0.putIfAbsent(text, it)
            cacheHitCount.incrementAndGet()
            return it
        }
        cacheMissCount.incrementAndGet()
        return null
    }

    private fun storeCached(text: String, forms: List<DeconjugationForm>) {
        if (text.length > MAX_CACHEABLE_INPUT_LENGTH || forms.size >= MAX_CACHED_FORMS_BASE + MAX_CACHED_FORMS_PER_CHAR * text.length)
            return
        cacheStoreCount.incrementAndGet()
        if (gen0.putIfAbsent(text, forms) == null && gen0Count.incrementAndGet() > maxCacheEntries) rotateGenerations()
    }

    private fun rotateGenerations() {
        if (!rotating.compareAndSet(false, true)) return
        try {
            val evicted = gen1?.size ?: 0
            gen1 = gen0
            gen0 = ConcurrentHashMap(4096)
            gen0Count.set(0)
            if (evicted > 0) cacheEvictionCount.addAndGet(evicted.toLong())
        } finally {
            rotating.set(false)
        }
    }

    fun getCacheStats() = DeconjugationCacheStats(
        cacheHitCount.get(), cacheMissCount.get(), cacheStoreCount.get(), cacheEvictionCount.get(),
        gen0.size + (gen1?.size ?: 0), maxCacheEntries, bfsCallCount.get(),
    )

    internal fun clearCacheForTesting() {
        gen0 = ConcurrentHashMap(4096)
        gen1 = null
        gen0Count.set(0)
        cacheHitCount.set(0)
        cacheMissCount.set(0)
        cacheStoreCount.set(0)
        cacheEvictionCount.set(0)
    }

    /** Ordered by text length descending, then ordinal; call sites must not re-sort. */
    fun deconjugate(text: String): List<DeconjugationForm> {
        tryGetCached(text)?.let { return it }
        if (text.isEmpty()) return emptyList()
        val result = runBfs(text)
        bfsCallCount.incrementAndGet()
        storeCached(text, result)
        return result
    }

    private fun runBfs(text: String): List<DeconjugationForm> {
        // seen doubles as the result set: every form reached is emitted, in discovery order
        val seen = HashSet<DeconjugationForm>(maxOf(text.length * 4, 32))
        val ordered = ArrayList<DeconjugationForm>(maxOf(text.length * 4, 32))
        var novel = ArrayList<DeconjugationForm>(20)
        val initial = DeconjugationForm(text, text, emptyArray(), null, emptyArray())
        seen.add(initial)
        ordered.add(initial)
        novel.add(initial)
        var newNovel = ArrayList<DeconjugationForm>(32)
        while (novel.isNotEmpty()) {
            newNovel.clear()
            for (form in novel) {
                if (shouldSkipForm(form)) continue
                applyIndexedRules(form, newNovel, seen, ordered)
            }
            val t = novel; novel = newNovel; newNovel = t
        }
        // Stable sort: same-text forms with different chains keep BFS discovery order
        return ordered.sortedWith(compareByDescending<DeconjugationForm> { it.text.length }.thenBy { it.text })
    }

    private fun applyIndexedRules(
        form: DeconjugationForm,
        newNovel: MutableList<DeconjugationForm>,
        seen: HashSet<DeconjugationForm>,
        ordered: MutableList<DeconjugationForm>,
    ) {
        val text = form.text
        val maxSuffix = minOf(maxConEndLength, text.length)
        for (suffixLen in 0..maxSuffix) {
            val entries = conEndIndex[text.substring(text.length - suffixLen)] ?: continue
            for (entry in entries) {
                when (entry.mode) {
                    RuleMode.OnlyFinal -> if (form.tags.isNotEmpty()) continue
                    RuleMode.NeverFinal -> if (form.tags.isEmpty()) continue
                    RuleMode.Rewrite -> if (text != entry.virtualRule.conEnd) continue
                    RuleMode.Context -> {
                        if (entry.rule.contextRule == "v1inftrap" && !v1InfTrapCheck(form)) continue
                        if (entry.rule.contextRule == "saspecial" && !saSpecialCheck(form, entry.rule)) continue
                        if (entry.rule.contextRule == "temirurule" && !temiruCheck(form, entry.rule)) continue
                    }
                    RuleMode.Standard -> {}
                }
                if (entry.rule.detail.isEmpty() && form.tags.isEmpty()) continue
                if (form.tags.isNotEmpty() && form.tags.last() != entry.virtualRule.conTag) continue

                val newText = text.substring(0, text.length - entry.virtualRule.conEnd.length) + entry.virtualRule.decEnd
                if (newText == form.originalText) continue

                val newForm = createNewForm(form, newText, entry.virtualRule.conTag, entry.virtualRule.decTag, entry.virtualRule.detail)
                if (seen.add(newForm)) {
                    ordered.add(newForm)
                    newNovel.add(newForm)
                }
            }
        }
    }

    private fun shouldSkipForm(form: DeconjugationForm) = form.text.isEmpty() ||
        form.text.length > form.originalText.length + MAX_TEXT_GROWTH_FROM_ORIGINAL ||
        form.tags.size > form.originalText.length + MAX_CHAIN_DEPTH_ABOVE_INPUT_LENGTH

    private fun createNewForm(form: DeconjugationForm, newText: String, conTag: String?, decTag: String?, detail: String): DeconjugationForm {
        val tags = ArrayList<String>(form.tags.size + 2)
        tags.addAll(form.tags)
        if (form.tags.isEmpty() && conTag != null) tags.add(conTag)
        if (decTag != null) tags.add(decTag)
        // A text already on the chain keeps its first position
        val seenText = SeenTextNode.append(form.seenChain ?: SeenTextNode.append(null, form.text), newText)
        return DeconjugationForm(newText, form.originalText, tags.toTypedArray(), seenText, form.process + detail)
    }

    private fun temiruCheck(form: DeconjugationForm, rule: DeconjugationRule): Boolean {
        val conEnd = rule.conEnd[0]
        if (!form.text.endsWith(conEnd)) return false
        val prefix = form.text.substring(0, form.text.length - conEnd.length)
        return prefix.endsWith('て') || prefix.endsWith('で')
    }

    private fun v1InfTrapCheck(form: DeconjugationForm) = !(form.tags.size == 1 && form.tags[0] == "stem-ren")

    private fun saSpecialCheck(form: DeconjugationForm, rule: DeconjugationRule): Boolean {
        if (form.text.isEmpty()) return false
        val conEnd = rule.conEnd[0]
        if (!form.text.endsWith(conEnd)) return false
        val prefixLength = form.text.length - conEnd.length
        return prefixLength <= 0 || form.text[prefixLength - 1] != 'さ'
    }

    companion object {
        const val DEFAULT_MAX_CACHE_ENTRIES = 50_000
        private const val MAX_CACHEABLE_INPUT_LENGTH = 40
        private const val MAX_CACHED_FORMS_BASE = 64
        private const val MAX_CACHED_FORMS_PER_CHAR = 12
        // Deconjugation shortens text; growth beyond this is spurious rule chaining
        private const val MAX_TEXT_GROWTH_FROM_ORIGINAL = 10
        // Deepest real conjugation chain is ~6 steps (食べさせられたくなかったらしい)
        private const val MAX_CHAIN_DEPTH_ABOVE_INPUT_LENGTH = 6

        /** The shared instance (C# Deconjugator.Instance). */
        val instance: Deconjugator by lazy { Deconjugator() }

        private fun loadRules(): Array<DeconjugationRule> {
            val json = Deconjugator::class.java.getResourceAsStream("/jiten/deconjugator.json")!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            // The file starts with // comments (System.Text.Json skipped them)
            val array = JSONArray(json.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n"))
            return Array(array.length()) { i ->
                val o = array.getJSONObject(i)
                fun strings(key: String): Array<String>? = when (val v = o.opt(key)) {
                    is String -> arrayOf(v)
                    is JSONArray -> Array(v.length()) { v.getString(it) }
                    else -> null // absent or JSON null
                }
                DeconjugationRule(
                    o.getString("type"), o.optString("contextrule").ifEmpty { null },
                    strings("dec_end")!!, strings("con_end")!!, strings("dec_tag"), strings("con_tag"),
                    o.optString("detail", ""),
                )
            }
        }
    }
}
