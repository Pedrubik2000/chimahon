package chimahon.chardict

/** A trait with a spoiler level (0 none, 1 minor, 2 major). AniList has none: Bee's VNDB side, kept for the card builder. */
data class CharacterTrait(val name: String, val spoiler: Int)

/**
 * Normalized character (Bee's `models.rs` Character). [role] is "main" | "primary" | "side" | "appears":
 * AniList MAIN is "primary", SUPPORTING "side", BACKGROUND "appears" ("main" is VNDB's protagonist).
 * The image fields are filled after download + resize.
 */
data class Character(
    val id: String,
    val name: String, // romanized, "Given Family"
    val nameOriginal: String, // Japanese, "Family Given"
    val role: String,
    val source: String = "anilist",
    val sex: String? = null, // "m" | "f"
    val age: String? = null, // a string: AniList may say "17-18"
    val height: Int? = null,
    val weight: Int? = null,
    val bloodType: String? = null,
    val birthday: List<Int>? = null, // [month, day]
    val description: String? = null,
    val aliases: List<String> = emptyList(),
    val spoilerAliases: List<String> = emptyList(),
    val personality: List<CharacterTrait> = emptyList(),
    val roles: List<CharacterTrait> = emptyList(),
    val engagesIn: List<CharacterTrait> = emptyList(),
    val subjectOf: List<CharacterTrait> = emptyList(),
    val imageUrl: String? = null,
    val imageBytes: ByteArray? = null,
    val imageExt: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val firstNameHint: String? = null, // given name romaji (AniList "first")
    val lastNameHint: String? = null, // family name romaji (AniList "last")
    val seiyuu: String? = null,
    val seiyuuImageUrl: String? = null,
    val seiyuuImageBytes: ByteArray? = null,
    val seiyuuImageExt: String? = null,
    val seiyuuImageWidth: Int? = null,
    val seiyuuImageHeight: Int? = null,
)

/** What a card shows (Bee's `DictSettings`; all on except spoilers). */
data class DictSettings(
    val showImage: Boolean = true,
    val showTag: Boolean = true,
    val showDescription: Boolean = true,
    val showTraits: Boolean = true,
    val showSpoilers: Boolean = false, // off (no spoilers, Pedro's rule): off drops AniList's spoiler names and ~!spoiler!~ text; on keeps both
    val honorifics: Boolean = true,
    val showSeiyuu: Boolean = true,
)

/** An AniList media as the search and relations queries give it: [type] is "MANGA" or "ANIME". */
data class MediaRef(val id: Int, val type: String) {
    override fun toString() = "$type:$id"

    companion object {
        /** "MANGA:97929" (an old plain "97929" counts as MANGA, as kumapie's characters.py reads it). */
        fun parse(s: String): MediaRef {
            val parts = s.split(':')
            return if (parts.size == 1) MediaRef(parts[0].trim().toInt(), "MANGA") else MediaRef(parts[1].trim().toInt(), parts[0].trim().uppercase())
        }
    }
}

/** A found media with its titles. */
data class MediaHit(val ref: MediaRef, val native: String?, val romaji: String?)
