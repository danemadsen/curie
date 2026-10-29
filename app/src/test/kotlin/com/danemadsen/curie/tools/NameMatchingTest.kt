package com.danemadsen.curie.tools

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatchingTest {
    @Test
    fun normalizationIgnoresCaseAccentsAndPunctuation() {
        assertEquals("zoe smith", NameMatching.normalize("  Zoë   Smith! "))
        assertEquals("whatsapp", NameMatching.normalize("WhatsApp"))
        assertEquals("mcdonalds", NameMatching.normalize("McDonald's"))
        assertEquals("", NameMatching.normalize("..."))
    }

    @Test
    fun scoresRankExactThenWordsThenPrefixesThenSubstrings() {
        val exact = NameMatching.score("youtube", "YouTube")
        val withoutSpaces = NameMatching.score("you tube", "YouTube")
        val wholeWord = NameMatching.score("maps", "Google Maps")
        val labelPrefix = NameMatching.score("spot", "Spotify")
        val wordPrefixes = NameMatching.score("goo ma", "Google Maps")
        val substring = NameMatching.score("tube", "YouTube")

        assertEquals(NameMatching.EXACT, exact, 1e-9)
        assertEquals(NameMatching.EXACT_WITHOUT_SPACES, withoutSpaces, 1e-9)
        assertEquals(NameMatching.ALL_WORDS, wholeWord, 1e-9)
        assertEquals(NameMatching.LABEL_PREFIX, labelPrefix, 1e-9)
        assertEquals(NameMatching.WORD_PREFIXES, wordPrefixes, 1e-9)
        assertEquals(NameMatching.SUBSTRING, substring, 1e-9)
    }

    @Test
    fun misspellingsScoreBelowEveryStructuralMatch() {
        val misspelled = NameMatching.score("spotfy", "Spotify")

        assertTrue(misspelled > 0.5)
        assertTrue(misspelled < NameMatching.SUBSTRING)
    }

    @Test
    fun unrelatedAndTooShortNamesDoNotMatch() {
        assertEquals(0.0, NameMatching.score("camera", "Calculator"), 1e-9)
        assertEquals(0.0, NameMatching.score("yo", "YouTube"), 1e-9)
        assertEquals(0.0, NameMatching.score("", "YouTube"), 1e-9)
    }

    @Test
    fun looseSimilarityIgnoresSpacesAndHasNoCutOff() {
        assertEquals(1.0, NameMatching.looseSimilarity("you tube", "YouTube"), 1e-9)
        assertEquals(0.6, NameMatching.looseSimilarity("youtub kids", "YouTube"), 1e-9)
        assertEquals(0.0, NameMatching.looseSimilarity("", "YouTube"), 1e-9)
    }

    @Test
    fun spellingSimilarityIsRelativeToTheLongerText() {
        assertEquals(1.0, NameMatching.spellingSimilarity("abc", "abc"), 1e-9)
        assertEquals(0.75, NameMatching.spellingSimilarity("abcd", "abce"), 1e-9)
        assertEquals(0.0, NameMatching.spellingSimilarity("abc", "xyz"), 1e-9)
    }
}

class AppMatcherTest {
    private fun app(label: String): LaunchableApp {
        val component = ComponentName("com.example.${label.lowercase().filter { it.isLetter() }}", "Main")
        return LaunchableApp(label, component)
    }

    private val apps = listOf(
        app("YouTube"),
        app("YouTube Music"),
        app("Google Maps"),
        app("Maps.me"),
        app("Google"),
        app("Camera"),
        app("Spotify"),
        app("Settings"),
    )

    @Test
    fun findsAppsBySpokenNames() {
        assertEquals("YouTube", AppMatcher.bestMatch("youtube", apps)?.label)
        assertEquals("YouTube", AppMatcher.bestMatch("You Tube", apps)?.label)
        assertEquals("YouTube Music", AppMatcher.bestMatch("youtube music", apps)?.label)
        assertEquals("Google", AppMatcher.bestMatch("google", apps)?.label)
        assertEquals("Spotify", AppMatcher.bestMatch("spotfy", apps)?.label)
        assertEquals("Camera", AppMatcher.bestMatch("the camera app", apps)?.label)
    }

    @Test
    fun tiesGoToTheShorterLabel() {
        assertEquals("Maps.me", AppMatcher.bestMatch("maps", apps)?.label)
    }

    @Test
    fun unknownAppsDoNotMatchButGetSuggestions() {
        assertNull(AppMatcher.bestMatch("whatsapp", apps))

        val suggestions = AppMatcher.suggestions("youtub kids", apps, limit = 3)
        assertEquals("YouTube", suggestions.firstOrNull())
        assertTrue(suggestions.size <= 3)
        assertTrue(AppMatcher.suggestions("zzzz", apps, limit = 3).isEmpty())
    }

    @Test
    fun anAppNamedWithFillerWordsStillMatchesAsSaid() {
        val withApp = listOf(app("App Store"), app("Store"))

        assertEquals("App Store", AppMatcher.bestMatch("app store", withApp)?.label)
    }
}

class ContactRankingTest {
    private fun entry(
        contactId: Long,
        name: String,
        value: String,
        preference: Int = 0,
        matchedByProvider: Boolean = false,
    ): ContactEntry {
        return ContactEntry(contactId, name, value, preference, matchedByProvider)
    }

    @Test
    fun anExactNameWins() {
        val entries = listOf(
            entry(1, "Mom", "+15550001"),
            entry(2, "Tom Mommsen", "+15550002"),
        )

        val lookup = ContactRanking.resolve("mom", entries)

        assertEquals(ContactLookup.Found("Mom", "+15550001"), lookup)
    }

    @Test
    fun aWholeFirstNameBeatsAPrefix() {
        val entries = listOf(
            entry(1, "John Smith", "+15550001"),
            entry(2, "Johnny Appleseed", "+15550002"),
        )

        val lookup = ContactRanking.resolve("John", entries)

        assertEquals(ContactLookup.Found("John Smith", "+15550001"), lookup)
    }

    @Test
    fun twoPeopleWithTheSameFirstNameAreAmbiguous() {
        val entries = listOf(
            entry(1, "John Smith", "+15550001"),
            entry(2, "John Doe", "+15550002"),
        )

        val lookup = ContactRanking.resolve("john", entries)

        assertEquals(ContactLookup.Ambiguous(listOf("John Smith", "John Doe")), lookup)
    }

    @Test
    fun duplicatesOfOnePersonUseTheirPreferredNumber() {
        val entries = listOf(
            entry(1, "Ada Lovelace", "+15550001", preference = 0),
            entry(1, "Ada Lovelace", "+15550002", preference = 3),
            entry(7, "Ada Lovelace", "+15550003", preference = 1),
        )

        val lookup = ContactRanking.resolve("ada lovelace", entries)

        assertEquals(ContactLookup.Found("Ada Lovelace", "+15550002"), lookup)
    }

    @Test
    fun providerMatchesCountEvenWhenTheNameDiffers() {
        // The contacts provider also matches nicknames: "Bob" finds Robert.
        val entries = listOf(entry(1, "Robert Jones", "+15550001", matchedByProvider = true))

        val lookup = ContactRanking.resolve("bob", entries)

        assertEquals(ContactLookup.Found("Robert Jones", "+15550001"), lookup)
    }

    @Test
    fun aMisheardNameStillFindsTheContact() {
        val entries = listOf(
            entry(1, "Catherine", "+15550001"),
            entry(2, "Peter", "+15550002"),
        )

        val lookup = ContactRanking.resolve("Katherine", entries)

        assertEquals(ContactLookup.Found("Catherine", "+15550001"), lookup)
    }

    @Test
    fun nobodyMatches() {
        val entries = listOf(entry(1, "Peter", "+15550002"))

        assertEquals(ContactLookup.NotFound, ContactRanking.resolve("zed", entries))
        assertEquals(ContactLookup.NotFound, ContactRanking.resolve("anyone", emptyList()))
    }
}
