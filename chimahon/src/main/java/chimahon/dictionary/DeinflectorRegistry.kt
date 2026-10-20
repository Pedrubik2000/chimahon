package chimahon.dictionary

import chimahon.dictionary.arabic.ArabicDeinflector
import chimahon.dictionary.chinese.ChineseDeinflector
import chimahon.dictionary.de.GermanDeinflector
import chimahon.dictionary.english.EnglishDeinflector
import chimahon.dictionary.es.SpanishDeinflector
import chimahon.dictionary.fr.FrenchDeinflector
import chimahon.dictionary.it.ItalianDeinflector
import chimahon.dictionary.ko.KoreanDeinflector
import chimahon.dictionary.ru.RussianDeinflector

/**
 * Registry to provide the appropriate [Deinflector] for a given language code.
 */
object DeinflectorRegistry {

    // A `when`, not a map: each deinflector builds its rules on first use (Arabic's regexes, Korean's and French's
    // tables took ~210 ms together on the Tab S7+), so only the asked language's is built. Japanese uses none.
    fun get(languageCode: String): Deinflector? = when (languageCode) {
        "ar" -> ArabicDeinflector
        "ko" -> KoreanDeinflector
        "en" -> EnglishDeinflector
        "zh" -> ChineseDeinflector
        "de" -> GermanDeinflector
        "fr" -> FrenchDeinflector
        "ru" -> RussianDeinflector
        "es" -> SpanishDeinflector
        "it" -> ItalianDeinflector
        else -> null
    }
}
