package app.escudo.android.ui

import app.escudo.android.R
import app.escudo.android.data.api.ApiException
import app.escudo.android.ui.components.userMessage
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class UserErrorsTest {
  @Test
  fun `technical failures become customer-facing messages`() {
    assertEquals(R.string.error_session, ApiException(401, "HTTP 401 /policy").userMessage())
    assertEquals(R.string.error_server, ApiException(503, "HTTP 503").userMessage())
    assertEquals(R.string.error_network, UnknownHostException("escudo-api").userMessage())
    assertEquals(R.string.error_generic, RuntimeException("boom").userMessage())
  }
}
