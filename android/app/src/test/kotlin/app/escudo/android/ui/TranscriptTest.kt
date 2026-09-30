package app.escudo.android.ui

import app.escudo.android.assistant.AssistantEvent.Role
import app.escudo.android.assistant.AssistantEvent.Transcript
import app.escudo.android.ui.assistant.ChatLine
import app.escudo.android.ui.assistant.withTranscript
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTest {
  @Test
  fun `deltas grow one line and final replaces it`() {
    val lines = emptyList<ChatLine>()
      .withTranscript(Transcript(Role.USER, "tuve un ", final = false))
      .withTranscript(Transcript(Role.USER, "choque", final = false))
      .withTranscript(Transcript(Role.USER, "Tuve un choque.", final = true))

    assertEquals(listOf(ChatLine(Role.USER, "Tuve un choque.")), lines)
  }

  @Test
  fun `a new speaker starts a new line`() {
    val lines = emptyList<ChatLine>()
      .withTranscript(Transcript(Role.USER, "Hola", final = true))
      .withTranscript(Transcript(Role.ASSISTANT, "Hola, ", final = false))
      .withTranscript(Transcript(Role.ASSISTANT, "Andrea", final = false))

    assertEquals(2, lines.size)
    assertEquals(ChatLine(Role.ASSISTANT, "Hola, Andrea", partial = true), lines.last())
  }

  @Test
  fun `final after a closed line of the same speaker is a new line`() {
    val lines = listOf(ChatLine(Role.ASSISTANT, "Listo."))
      .withTranscript(Transcript(Role.ASSISTANT, "¿Algo más?", final = true))
    assertEquals(2, lines.size)
  }
}
