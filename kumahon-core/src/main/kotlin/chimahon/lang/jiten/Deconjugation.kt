// Ported from Jiten (github.com/Sirush/Jiten, Apache-2.0, commit 44b48a4): Jiten.Parser/Deconjugation/DeconjugationForm.cs,
// DeconjugationRule.cs, DeconjugationVirtualRule.cs, StringArrayConverter.cs
package chimahon.lang.jiten

/** A rule of deconjugator.json; dec_end/con_end/dec_tag/con_tag may be one string or a list (StringArrayConverter). */
class DeconjugationRule(
    val type: String,
    val contextRule: String?,
    val decEnd: Array<String>,
    val conEnd: Array<String>,
    val decTag: Array<String>?,
    val conTag: Array<String>?,
    val detail: String,
)

data class DeconjugationVirtualRule(
    val decEnd: String,
    val conEnd: String,
    val decTag: String?,
    val conTag: String?,
    val detail: String,
)

/** One step of a deconjugation chain. Arrays are never mutated after construction. Equality covers [seenText] as a set. */
class DeconjugationForm internal constructor(
    val text: String,
    val originalText: String,
    val tags: Array<String>,
    internal val seenChain: SeenTextNode?,
    val process: Array<String>,
) {
    private val hash = computeHash(text, originalText, tags, process, seenChain)

    /** Like C#'s public constructor: empty tags, process steps and seen texts are dropped. */
    constructor(text: String, originalText: String, tags: Iterable<String>?, seenText: Iterable<String>?, process: Iterable<String>?) :
        this(text, originalText, tags?.filter { it.isNotEmpty() }?.toTypedArray() ?: emptyArray(),
            seenText?.filter { it.isNotEmpty() }?.fold(null as SeenTextNode?) { chain, s -> SeenTextNode.append(chain, s) },
            process?.filter { it.isNotEmpty() }?.toTypedArray() ?: emptyArray())

    /** Chain order matters to callers: compound matching sorts these with an unstable sort. */
    val seenText: Array<String> by lazy { SeenTextNode.toArray(seenChain) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeconjugationForm) return false
        return hash == other.hash && text == other.text && originalText == other.originalText &&
            tags.contentEquals(other.tags) && process.contentEquals(other.process) &&
            SeenTextNode.setEquals(seenChain, other.seenChain)
    }

    override fun hashCode() = hash

    private companion object {
        fun computeHash(text: String, originalText: String, tags: Array<String>, process: Array<String>, seen: SeenTextNode?): Int {
            var h = text.hashCode()
            h = 31 * h + originalText.hashCode()
            for (t in tags) h = 31 * h + t.hashCode()
            for (p in process) h = 31 * h + p.hashCode()
            return 31 * h + (seen?.setHash ?: 0)
        }
    }
}

/** Immutable linked set of chain texts; the newest text is the head, so appending never copies. */
internal class SeenTextNode private constructor(val text: String, val prev: SeenTextNode?) {
    val count: Int = (prev?.count ?: 0) + 1

    /** XOR of the members' hashes: order-independent, so equal sets hash equal. */
    val setHash: Int = (prev?.setHash ?: 0) xor text.hashCode()

    companion object {
        fun contains(node: SeenTextNode?, text: String): Boolean {
            var n = node
            while (n != null) {
                if (n.text == text) return true
                n = n.prev
            }
            return false
        }

        /** Returns the same chain when the text is already a member. */
        fun append(chain: SeenTextNode?, text: String): SeenTextNode =
            if (contains(chain, text)) chain!! else SeenTextNode(text, chain)

        fun setEquals(a: SeenTextNode?, b: SeenTextNode?): Boolean {
            if (a === b) return true
            if ((a?.count ?: 0) != (b?.count ?: 0) || (a?.setHash ?: 0) != (b?.setHash ?: 0)) return false
            var n = a
            while (n != null) {
                if (!contains(b, n.text)) return false
                n = n.prev
            }
            return true
        }

        fun toArray(chain: SeenTextNode?): Array<String> {
            if (chain == null) return emptyArray()
            val result = arrayOfNulls<String>(chain.count)
            var n: SeenTextNode? = chain
            while (n != null) {
                result[n.count - 1] = n.text
                n = n.prev
            }
            @Suppress("UNCHECKED_CAST")
            return result as Array<String>
        }
    }
}
