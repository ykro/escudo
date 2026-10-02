package app.escudo.android

import android.util.Log

/**
 * One-line markers of what the assistant and the approval flow are doing, under the logcat tag
 * `EscudoScenario`. `scripts/chat_scenario.sh` waits on them (turn finished, approval push
 * arrived, approval granted) instead of sleeping, so a rehearsal runs at the real system speed.
 * No personal data: ids and tool names only.
 */
object ScenarioLog {
  const val TAG = "EscudoScenario"

  fun mark(event: String, detail: String = "") {
    Log.i(TAG, if (detail.isEmpty()) event else "$event $detail")
  }
}
