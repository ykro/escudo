package app.escudo.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.escudo.android.assistant.AssistantEvent
import app.escudo.android.assistant.FileSource
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The spoken voice path, end to end against the deployed backend: a recorded Spanish request
 * (testdata/voice/01_choque.wav) streams at real time through FileSource → the assistant
 * WebSocket → Gemini Live, which must transcribe it and call an MCP tool.
 *
 * Needs the network and the seeded customer's credentials, so it only runs with LIVE_TESTS=1
 * (`make live-test`); otherwise the runner filters it out by its [LiveTest] annotation.
 */
@LiveTest
@RunWith(AndroidJUnit4::class)
class LiveVoiceTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Before
  fun onlyWhenAsked() {
    assumeTrue("set LIVE_TESTS=1 to run tests against the cloud", InstrumentationRegistry.getArguments().getString("LIVE_TESTS") == "1")
    assumeFalse("the fake flavor has no backend", BuildConfig.USE_FAKE_API)
  }

  @After
  fun tearDown() = scope.cancel()

  @Test
  fun spokenRequestIsTranscribedAndTriggersAToolCall() = runBlocking {
    val app = ApplicationProvider.getApplicationContext<EscudoApp>()
    val session = app.container.assistant.create()
    val events = Collections.synchronizedList(mutableListOf<AssistantEvent>())
    val connected = CompletableDeferred<Unit>()
    val sentBytes = AtomicLong()
    val receivedAudioBytes = AtomicLong()
    scope.launch {
      session.events.collect { event ->
        events += event
        when (event) {
          AssistantEvent.Connected -> connected.complete(Unit)
          is AssistantEvent.Audio -> receivedAudioBytes.addAndGet(event.pcm24k.size.toLong())
          else -> Unit
        }
      }
    }

    session.connect()
    withTimeout(60_000) { connected.await() }

    // Same calls the assistant screen makes for push-to-talk, with a file instead of the mic.
    val played = CompletableDeferred<Unit>()
    session.startTalking()
    FileSource(scope, "01_choque", { app.assets.open("voice/01_choque.wav") }).start(
      onLevel = {},
      onChunk = { sentBytes.addAndGet(it.size.toLong()); session.sendAudio(it) },
      onEnd = { session.stopTalking(); played.complete(Unit) },
    )
    withTimeout(30_000) { played.await() }
    assertTrue("audio streamed: ${sentBytes.get()} bytes", sentBytes.get() > 200_000)

    // The server heard it (its own transcript of the customer) and acted (a tool call).
    withTimeout(90_000) {
      while (events.none { it is AssistantEvent.ToolActivity }) delay(250)
    }
    val heard = synchronized(events) {
      events.filterIsInstance<AssistantEvent.Transcript>().filter { it.role == AssistantEvent.Role.USER }.joinToString(" ") { it.text }
    }
    assertTrue("server transcript of the spoken request: '$heard'", heard.contains("choque", ignoreCase = true))
    val tools = synchronized(events) { events.filterIsInstance<AssistantEvent.ToolActivity>().map { it.tool } }
    assertTrue("tool calls: $tools", tools.isNotEmpty())

    // And it answers out loud (24 kHz audio frames back).
    withTimeout(60_000) {
      while (receivedAudioBytes.get() == 0L) delay(250)
    }
    session.close()
  }
}
