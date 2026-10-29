package com.danemadsen.curie.call

import android.content.Intent
import com.danemadsen.curie.audio.FakeMicrophone
import com.danemadsen.curie.audio.FakeSpeechOutput
import com.danemadsen.curie.audio.FocusChange
import com.danemadsen.curie.audio.RecordingPurpose
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatRole
import com.danemadsen.curie.engine.chat.ContentPart
import com.danemadsen.curie.engine.chat.GeneratedContent
import com.danemadsen.curie.engine.chat.ToolCall
import com.danemadsen.curie.engine.chat.ToolDefinition
import com.danemadsen.curie.engine.generation.FinishReason
import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationResult
import com.danemadsen.curie.inference.ContextWindow
import com.danemadsen.curie.inference.EngineUnavailableException
import com.danemadsen.curie.inference.FakeToolExecutor
import com.danemadsen.curie.inference.SpeechSink
import com.danemadsen.curie.tools.ToolOrigin
import com.danemadsen.curie.tools.ToolOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the fake model says next in a call. */
private data class CallReply(
    val text: String = "",
    val speechSamples: Int = 0,
    val toolCalls: List<ToolCall> = emptyList(),
    val waitsForCancel: Boolean = false,
    val fails: Boolean = false,
)

private class FakeCallAssistant : CallAssistant {
    val replies = ArrayDeque<CallReply>()
    val prompts = ArrayList<List<ChatMessage>>()

    /** Whether each answer round could call tools. */
    val toolCallsAllowed = ArrayList<Boolean>()

    val synthesized = ArrayList<String>()
    var isAvailable = true
    var warmUps = 0
        private set
    var cancels = 0
        private set
    private var cancelled: CompletableDeferred<Unit>? = null

    override suspend fun prepare() {
        if (!isAvailable) {
            throw EngineUnavailableException("not downloaded")
        }
    }

    override suspend fun warmUp(messages: List<ChatMessage>, tools: List<ToolDefinition>) {
        warmUps++
    }

    override suspend fun respond(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        listener: GenerationListener,
        speech: SpeechSink,
        window: ContextWindow,
        allowToolCalls: Boolean,
    ): GenerationResult {
        prompts.add(messages)
        toolCallsAllowed.add(allowToolCalls)
        val reply = replies.removeFirst()
        if (reply.fails) {
            throw IllegalStateException("the model broke")
        }
        if (reply.text.isNotEmpty()) {
            listener.onText(reply.text)
        }
        if (reply.speechSamples > 0) {
            speech.onSamples(FloatArray(reply.speechSamples) { 0.1f })
        }
        if (reply.waitsForCancel) {
            val signal = CompletableDeferred<Unit>()
            cancelled = signal
            signal.await()
            return result(reply.text, emptyList(), FinishReason.CANCELLED)
        }
        return result(reply.text, reply.toolCalls, FinishReason.END_OF_TURN)
    }

    override suspend fun synthesize(text: String, speech: SpeechSink): Boolean {
        synthesized.add(text)
        speech.onSamples(FloatArray(2_400) { 0.1f })
        return true
    }

    override fun cancel() {
        cancels++
        cancelled?.complete(Unit)
    }

    private fun result(text: String, toolCalls: List<ToolCall>, finishReason: FinishReason): GenerationResult {
        val content = GeneratedContent(intArrayOf(1, 2, 3), IntArray(0))
        return GenerationResult(content, text, toolCalls, emptyList(), finishReason)
    }
}

private class FakeCallEnvironment : CallEnvironment {
    val launched = ArrayList<Intent>()
    var isUnlocked = true

    /** False: another app keeps the audio, and focus requests are refused. */
    var grantsFocus = true

    var hasFocus = false
        private set
    var focusRequests = 0
        private set
    var onFocusChange: ((FocusChange) -> Unit)? = null
        private set

    override fun requestAudioFocus(onChange: (FocusChange) -> Unit): Boolean {
        focusRequests++
        hasFocus = grantsFocus
        onFocusChange = onChange
        return grantsFocus
    }

    override fun abandonAudioFocus() {
        hasFocus = false
    }

    override suspend fun ensureUnlocked(): Boolean = isUnlocked

    override fun launch(intent: Intent): Boolean {
        launched.add(intent)
        return true
    }
}

/**
 * The call's state machine with fakes all round: an utterance becomes a turn, the answer plays,
 * the user barges in, a tool's app launch waits for the end of the answer and ends the call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallControllerTest {
    private val assistant = FakeCallAssistant()
    private val tools = FakeToolExecutor()
    private val microphone = FakeMicrophone()
    private val output = FakeSpeechOutput()
    private val environment = FakeCallEnvironment()

    private fun TestScope.newController(): CallController = CallController(
        assistant = assistant,
        tools = tools,
        microphone = microphone,
        output = output,
        environment = environment,
        systemPrompt = "You are on a call.",
        texts = CallTexts(failure = "Sorry.", locked = "Locked"),
        clock = { testScheduler.currentTime },
    )

    /** The user says something for [speechMs], then is silent long enough to end the utterance. */
    private fun TestScope.userSays(speechMs: Int = 500) {
        microphone.say(speechMs)
        microphone.beSilent(800)
        runCurrent()
    }

    private fun audioOf(message: ChatMessage): ContentPart.Audio = message.parts.single() as ContentPart.Audio

    @Test
    fun aCallGoesFromUtteranceToAnswerToBargeInToADeferredAppLaunch() = runTest {
        val controller = newController()
        val job = controller.start(this)
        runCurrent()

        // Connected and listening, with the call's audio focus and a warm cache
        assertEquals(CallStage.LISTENING, controller.state.value.stage)
        assertNotNull(controller.state.value.connectedAtMs)
        assertTrue(environment.hasFocus)
        assertEquals(1, assistant.warmUps)
        assertEquals(RecordingPurpose.CALL, microphone.purpose)

        // 1. An utterance is answered with speech, which plays to its end
        assistant.replies.addLast(CallReply(text = "Hello there.", speechSamples = 4_800))
        microphone.say(500)
        runCurrent()
        assertTrue(controller.state.value.isUserSpeaking)
        microphone.beSilent(800)
        runCurrent()

        assertEquals(CallStage.SPEAKING, controller.state.value.stage)
        assertEquals("Hello there.", controller.state.value.assistantCaption)
        val firstPrompt = assistant.prompts.single()
        assertEquals("You are on a call.", (firstPrompt[0].parts.single() as ContentPart.Text).text)
        // The speech, its pre-roll and a little of the silence after it
        val utterance = audioOf(firstPrompt[1])
        assertTrue(utterance.input.sampleCount in 16_000 * 500 / 1_000..16_000 * 1_000 / 1_000)
        assertTrue(output.isAnswerComplete)

        output.finishPlaying()
        runCurrent()
        assertEquals(CallStage.LISTENING, controller.state.value.stage)

        // 2. The user barges in while Curie speaks: she stops, and what she said stays in the history
        assistant.replies.addLast(CallReply(text = "Let me tell you a long story", speechSamples = 4_800, waitsForCancel = true))
        userSays()
        assertEquals(CallStage.SPEAKING, controller.state.value.stage)
        val stopsBefore = output.stops

        microphone.say(400)
        runCurrent()
        assertEquals(1, assistant.cancels)
        assertTrue(output.stops > stopsBefore)
        assertEquals(CallStage.LISTENING, controller.state.value.stage)
        assertTrue(controller.state.value.isUserSpeaking)

        // 3. The barge-in becomes the next turn; it asks for an app, which opens after Curie's answer
        val youtube = Intent()
        val openApp = ToolCall("open_app", JsonObject(mapOf("name" to JsonPrimitive("YouTube"))))
        tools.nextOutcome = ToolOutcome(
            result = JsonObject(mapOf("opened" to JsonPrimitive("YouTube"))),
            summary = "Opening YouTube",
            icon = "brand-youtube",
            deferredLaunch = youtube,
            success = true,
        )
        assistant.replies.addLast(CallReply(toolCalls = listOf(openApp)))
        assistant.replies.addLast(CallReply(text = "Opening YouTube."))
        microphone.beSilent(800)
        runCurrent()

        val thirdPrompt = assistant.prompts[2]
        val roles = thirdPrompt.map { message -> message.role }
        val expectedRoles = listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER)
        assertEquals(expectedRoles, roles)
        assertEquals(ToolOrigin.CALL, tools.calls.single().second)

        // The answer had no speech of its own: it was read aloud, and nothing launched before it ended
        val lastPrompt = assistant.prompts[3]
        assertEquals(ChatRole.TOOL, lastPrompt.last().role)
        assertEquals(listOf("Opening YouTube."), assistant.synthesized)
        assertTrue(environment.launched.isEmpty())

        output.finishPlaying()
        runCurrent()
        assertEquals(listOf(youtube), environment.launched)
        assertEquals(CallStage.ENDED, controller.state.value.stage)
        assertEquals(CallEndReason.APP_LAUNCHED, controller.state.value.endReason)
        assertFalse(environment.hasFocus)
        assertTrue(job.isCompleted)
    }

    @Test
    fun aWebLookupInACallNeverOpensAndTheModelAnswers() = runTest {
        val controller = newController()
        val job = controller.start(this)
        runCurrent()

        // Asked a question, the model looks it up instead of answering
        val search = Intent()
        val webSearch = ToolCall("web_search", JsonObject(mapOf("query" to JsonPrimitive("capital of France"))))
        tools.nextOutcome = ToolOutcome(
            result = JsonObject(mapOf("status" to JsonPrimitive("search_opened"))),
            summary = "Searching the web",
            icon = "search",
            deferredLaunch = search,
            success = true,
            isWebLookup = true,
        )
        assistant.replies.addLast(CallReply(toolCalls = listOf(webSearch)))
        assistant.replies.addLast(CallReply(text = "It's Paris."))
        userSays()

        // It read that nothing opened, and its next round could only answer
        assertEquals(listOf(true, false), assistant.toolCallsAllowed)
        val toolResult = (assistant.prompts.last().last().parts.single() as ContentPart.Text).text
        assertTrue(toolResult.contains("not_opened"))

        // The answer plays and the call goes on: a search would have ended it
        output.finishPlaying()
        runCurrent()
        assertTrue(environment.launched.isEmpty())
        assertEquals(CallStage.LISTENING, controller.state.value.stage)

        controller.hangUp()
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun hangingUpEndsTheCall() = runTest {
        val controller = newController()
        val job = controller.start(this)
        runCurrent()

        controller.hangUp()
        runCurrent()

        assertEquals(CallStage.ENDED, controller.state.value.stage)
        assertEquals(CallEndReason.HUNG_UP, controller.state.value.endReason)
        assertTrue(job.isCompleted)
        assertFalse(environment.hasFocus)
    }

    @Test
    fun withoutTheModelTheCallEnds() = runTest {
        assistant.isAvailable = false
        val controller = newController()

        controller.start(this)
        runCurrent()

        assertEquals(CallStage.ENDED, controller.state.value.stage)
        assertEquals(CallEndReason.MODEL_UNAVAILABLE, controller.state.value.endReason)
    }

    @Test
    fun aMutedMicrophoneHearsNothing() = runTest {
        val controller = newController()
        val job = controller.start(this)
        runCurrent()

        controller.setMuted(true)
        userSays()

        assertTrue(assistant.prompts.isEmpty())
        assertEquals(0f, controller.inputLevel)
        assertTrue(controller.state.value.isMuted)
        job.cancel()
    }

    @Test
    fun toolsWaitForTheUserToUnlockThePhone() = runTest {
        environment.isUnlocked = false
        output.finishesByItself = true
        val controller = newController()
        val job = controller.start(this)
        runCurrent()

        val timer = ToolCall("set_timer", JsonObject(mapOf("seconds" to JsonPrimitive(60))))
        assistant.replies.addLast(CallReply(toolCalls = listOf(timer)))
        assistant.replies.addLast(CallReply(text = "Please unlock your phone first."))
        userSays()

        assertTrue(tools.calls.isEmpty())
        val toolMessage = assistant.prompts[1].last()
        val result = (toolMessage.parts.single() as ContentPart.Text).text
        assertTrue(result.contains("locked"))
        assertEquals(CallStage.LISTENING, controller.state.value.stage)
        job.cancel()
    }

    @Test
    fun aFailedAnswerIsForgottenAndTheCallGoesOn() = runTest {
        output.finishesByItself = true
        val controller = newController()
        val job = controller.start(this)
        runCurrent()

        assistant.replies.addLast(CallReply(fails = true))
        userSays()
        assertEquals(CallStage.LISTENING, controller.state.value.stage)
        assertEquals("Sorry.", controller.state.value.assistantCaption)

        // The next turn's prompt holds only the new utterance
        assistant.replies.addLast(CallReply(text = "Yes?", speechSamples = 2_400))
        userSays()
        assertEquals(2, assistant.prompts.last().size)
        job.cancel()
    }

    @Test
    fun anotherAppTakingTheAudioPausesCurie() = runTest {
        val controller = newController()
        val job = controller.start(this)
        runCurrent()

        val onFocusChange = assertNotNull(environment.onFocusChange)
        onFocusChange(FocusChange.PAUSE)
        assertTrue(output.isPaused)
        onFocusChange(FocusChange.GAINED)
        assertFalse(output.isPaused)

        controller.setSpeakerOn(false)
        assertFalse(output.speakerOn)
        assertFalse(controller.state.value.isSpeakerOn)
        job.cancel()
    }

    @Test
    fun anotherAppTakingTheAudioForGoodSilencesTheAnswerButTheCallGoesOn() = runTest {
        val controller = newController()
        val job = controller.start(this)
        runCurrent()

        // Curie speaks; another app talks over her, then takes the audio for good
        assistant.replies.addLast(CallReply(text = "Hello there.", speechSamples = 4_800))
        userSays()
        assertEquals(CallStage.SPEAKING, controller.state.value.stage)
        val stopsBefore = output.stops
        val onFocusChange = assertNotNull(environment.onFocusChange)
        onFocusChange(FocusChange.PAUSE)
        onFocusChange(FocusChange.LOST)
        runCurrent()

        // The answer is over, not interrupted: the caption stays and nobody seems to be speaking
        assertTrue(output.stops > stopsBefore)
        assertEquals(CallStage.LISTENING, controller.state.value.stage)
        assertFalse(controller.state.value.isUserSpeaking)
        assertEquals("Hello there.", controller.state.value.assistantCaption)

        // The next answer asks for the audio again, and is heard
        assistant.replies.addLast(CallReply(text = "Yes?", speechSamples = 2_400))
        val samplesBefore = output.samplesWritten
        userSays()
        assertEquals(2, environment.focusRequests)
        assertFalse(output.isPaused)
        assertEquals(samplesBefore + 2_400, output.samplesWritten)
        job.cancel()
    }

    @Test
    fun withoutTheAudioFocusAnswersOnlyShowAsCaptions() = runTest {
        environment.grantsFocus = false
        val controller = newController()
        val job = controller.start(this)
        runCurrent()

        assistant.replies.addLast(CallReply(text = "I can't speak right now."))
        userSays()

        // Nothing was read aloud or played, the answer still shows, and the call listens on
        assertTrue(assistant.synthesized.isEmpty())
        assertEquals(0, output.samplesWritten)
        assertEquals("I can't speak right now.", controller.state.value.assistantCaption)
        assertEquals(CallStage.LISTENING, controller.state.value.stage)
        assertEquals(2, environment.focusRequests)
        job.cancel()
    }
}
