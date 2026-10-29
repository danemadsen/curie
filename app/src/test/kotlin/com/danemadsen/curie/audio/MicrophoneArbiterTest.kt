package com.danemadsen.curie.audio

import com.danemadsen.curie.audio.MicrophoneArbiter.Owner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneArbiterTest {
    @Test
    fun freeMicrophoneGoesToAnyRequester() {
        val arbiter = MicrophoneArbiter()

        assertTrue(arbiter.acquire(Owner.WAKE_WORD))
        assertEquals(Owner.WAKE_WORD, arbiter.owner.value)
    }

    @Test
    fun callsAndVoiceMessagesPreemptTheWakeWord() {
        val arbiter = MicrophoneArbiter()
        arbiter.acquire(Owner.WAKE_WORD)

        assertTrue(arbiter.acquire(Owner.CALL))
        assertEquals(Owner.CALL, arbiter.owner.value)
    }

    @Test
    fun theWakeWordWaitsForAFreeMicrophone() {
        val arbiter = MicrophoneArbiter()
        arbiter.acquire(Owner.VOICE_MESSAGE)

        assertFalse(arbiter.acquire(Owner.WAKE_WORD))
        assertEquals(Owner.VOICE_MESSAGE, arbiter.owner.value)
    }

    @Test
    fun callsAndVoiceMessagesDoNotPreemptEachOther() {
        val arbiter = MicrophoneArbiter()
        arbiter.acquire(Owner.CALL)

        assertFalse(arbiter.acquire(Owner.VOICE_MESSAGE))
        assertEquals(Owner.CALL, arbiter.owner.value)
    }

    @Test
    fun onlyTheHolderReleases() {
        val arbiter = MicrophoneArbiter()
        arbiter.acquire(Owner.CALL)

        arbiter.release(Owner.WAKE_WORD)
        assertEquals(Owner.CALL, arbiter.owner.value)

        arbiter.release(Owner.CALL)
        assertNull(arbiter.owner.value)
    }

    @Test
    fun acquiringTwiceIsIdempotent() {
        val arbiter = MicrophoneArbiter()

        assertTrue(arbiter.acquire(Owner.CALL))
        assertTrue(arbiter.acquire(Owner.CALL))
        assertEquals(Owner.CALL, arbiter.owner.value)
    }
}
