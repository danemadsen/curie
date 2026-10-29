package com.danemadsen.curie.assistant.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordMatcherTest {
    @Test
    fun acceptsTheWakePhraseAndCommonRecognizerSpellings() {
        val accepted = listOf(
            "hey curie",
            "Hey Curie, what's the weather?",
            "hey curry",
            "hey kiri",
            "hey query",
            "hey cury",
            "hey curi",
            "hey kerry",
            "hey courier",
            "hey cory",
            "hey corey",
            "hey kyrie",
            "hi curie",
            "hay curie",
            "ok curie",
            "okay curie",
            "heycurie",
            "hey cu rie",
            "a curie",
            "Hey Curié",
            "so I said hey curie",
        )
        for (transcript in accepted) {
            assertNotNull(transcript, WakeWordMatcher.findMatch(transcript))
        }
    }

    @Test
    fun acceptsNearMissesWithinThePhoneticThreshold() {
        // "cure" is 0.875 and "carry" 0.9 similar to "Curie": accepted by design (and a known false-accept risk).
        assertNotNull(WakeWordMatcher.findMatch("hey cure"))
        assertNotNull(WakeWordMatcher.findMatch("hey carry"))
    }

    @Test
    fun rejectsOtherNamesAndOrdinarySpeech() {
        val rejected = listOf(
            "",
            "curie",
            "hey",
            "hey car",
            "hey core",
            "hey siri",
            "hey jury",
            "hey harry",
            "hey gary",
            "hey google",
            "hey curious",
            "I'd like a curry",
            "a curry please",
            "marie curie was a physicist",
            "the courier is late",
        )
        for (transcript in rejected) {
            assertNull(transcript, WakeWordMatcher.findMatch(transcript))
        }
    }

    @Test
    fun aGreetingGluedToTheNameNeedsAKnownSpellingOfIt() {
        for (transcript in listOf("heycurie", "hicurry", "okcurie", "haykiri", "so heycurie")) {
            assertNotNull(transcript, WakeWordMatcher.findMatch(transcript))
        }

        // Words that merely begin like a greeting: "hi" + "ckory" sounds like Curie.
        val ordinarySpeech = listOf(
            "hickory",
            "I love hickory smoked bacon",
            "hickory dickory dock",
            "the hickory tree",
            "hikari",
            "heycure",
        )
        for (transcript in ordinarySpeech) {
            assertNull(transcript, WakeWordMatcher.findMatch(transcript))
        }
    }

    @Test
    fun theWeakPrefixOnlyCountsAtTheStartBeforeTheExactName() {
        assertNotNull(WakeWordMatcher.findMatch("a curie"))
        assertNull(WakeWordMatcher.findMatch("tell a curie"))
        assertNull(WakeWordMatcher.findMatch("a curry"))
    }

    @Test
    fun reportsWhereTheNameIs() {
        val match = WakeWordMatcher.findMatch("well hey curie")

        assertNotNull(match)
        assertEquals(2, match?.nameIndex)
        assertEquals(1.0, match?.score ?: 0.0, 1e-9)
    }

    @Test
    fun scoresFollowThePhoneticDistance() {
        assertEquals(1.0, WakeWordMatcher.nameScore("curie"), 1e-9)
        assertEquals(0.9, WakeWordMatcher.nameScore("kari"), 1e-9)
        assertEquals(0.875, WakeWordMatcher.nameScore("cure"), 1e-9)
        assertTrue(WakeWordMatcher.nameScore("car") < WakeWordMatcher.THRESHOLD)
        assertTrue(WakeWordMatcher.nameScore("gary") < WakeWordMatcher.THRESHOLD)
    }

    @Test
    fun phoneticKeysMergeSpellingsOfTheSameSound() {
        assertEquals("kuri", WakeWordMatcher.phoneticKey("curie"))
        assertEquals("kuri", WakeWordMatcher.phoneticKey("curry"))
        assertEquals("keri", WakeWordMatcher.phoneticKey("query"))
        assertEquals("kur", WakeWordMatcher.phoneticKey("cure"))
    }

    @Test
    fun picksTheBestHypothesis() {
        val hypotheses = listOf("hey there", "hey curry", "hey curie")

        val match = WakeWordMatcher.findMatch(hypotheses)

        assertEquals(1.0, match?.score ?: 0.0, 1e-9)
        assertNull(WakeWordMatcher.findMatch(listOf("hello", "help me")))
    }

    @Test
    fun wordsDropPunctuationAccentsAndApostrophes() {
        val words = WakeWordMatcher.words("Hey, Curié! I'd")

        assertEquals(listOf("hey", "curie", "id"), words)
        assertTrue(WakeWordMatcher.words("  ...  ").isEmpty())
    }
}

class PartialResultStabilizerTest {
    private val match = WakeWordMatch(score = 1.0, nameIndex = 1)

    @Test
    fun aSinglePartialIsNotEnough() {
        val stabilizer = PartialResultStabilizer()

        assertFalse(stabilizer.shouldFire(match, isFinal = false))
    }

    @Test
    fun theSameHitInTwoConsecutivePartialsFires() {
        val stabilizer = PartialResultStabilizer()

        stabilizer.shouldFire(match, isFinal = false)
        assertTrue(stabilizer.shouldFire(match, isFinal = false))
    }

    @Test
    fun aFinalResultFiresAtOnce() {
        val stabilizer = PartialResultStabilizer()

        assertTrue(stabilizer.shouldFire(match, isFinal = true))
    }

    @Test
    fun aPartialWithoutTheHitBreaksTheStreak() {
        val stabilizer = PartialResultStabilizer()

        stabilizer.shouldFire(match, isFinal = false)
        stabilizer.shouldFire(null, isFinal = false)
        assertFalse(stabilizer.shouldFire(match, isFinal = false))
    }

    @Test
    fun aHitThatMovedIsANewHit() {
        val stabilizer = PartialResultStabilizer()
        val movedMatch = WakeWordMatch(score = 1.0, nameIndex = 3)

        stabilizer.shouldFire(match, isFinal = false)
        assertFalse(stabilizer.shouldFire(movedMatch, isFinal = false))
        assertTrue(stabilizer.shouldFire(movedMatch, isFinal = false))
    }

    @Test
    fun resetForgetsThePreviousPartial() {
        val stabilizer = PartialResultStabilizer()

        stabilizer.shouldFire(match, isFinal = false)
        stabilizer.reset()
        assertFalse(stabilizer.shouldFire(match, isFinal = false))
    }
}
