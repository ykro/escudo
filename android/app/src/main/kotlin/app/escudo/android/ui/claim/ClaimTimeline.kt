package app.escudo.android.ui.claim

import app.escudo.android.data.model.Claim
import app.escudo.android.data.model.ClaimStatus

/**
 * One row of the claim timeline. [done] steps show their detail; [waitingForApproval] steps are
 * the agent's pauses (the only amber on the screen); the rest read "pending".
 */
data class TimelineStep(val kind: Kind, val done: Boolean, val waitingForApproval: Boolean = false) {
  enum class Kind { OPEN, ASSESSED, TOW, REPAIR, RENTAL, DEDUCTIBLE, SIGNED }
}

/** Which timeline step each approval-gated tool unlocks. */
fun stepForTool(tool: String): TimelineStep.Kind? = when (tool) {
  "select_repair_shop" -> TimelineStep.Kind.REPAIR
  "accept_deductible" -> TimelineStep.Kind.DEDUCTIBLE
  "sign_claim" -> TimelineStep.Kind.SIGNED
  "request_rental_car" -> TimelineStep.Kind.RENTAL
  else -> null
}

/**
 * Derives the timeline from the claim's own fields instead of trusting status order alone:
 * the agent may do tow, repair and deductible in any order.
 */
fun Claim.timeline(pendingTools: Set<String> = emptySet()): List<TimelineStep> {
  val waiting = pendingTools.mapNotNull(::stepForTool).toSet()
  fun step(kind: TimelineStep.Kind, done: Boolean) = TimelineStep(kind, done, waitingForApproval = !done && kind in waiting)
  return buildList {
    add(step(TimelineStep.Kind.OPEN, done = true))
    add(step(TimelineStep.Kind.ASSESSED, done = assessment != null))
    add(step(TimelineStep.Kind.TOW, done = tow?.provider != null))
    add(step(TimelineStep.Kind.REPAIR, done = repair?.shopId != null || repair?.shopName != null))
    if (rental != null || TimelineStep.Kind.RENTAL in waiting) add(step(TimelineStep.Kind.RENTAL, done = rental != null))
    add(step(TimelineStep.Kind.DEDUCTIBLE, done = deductibleAccepted == true || status == ClaimStatus.DEDUCTIBLE_ACCEPTED))
    add(step(TimelineStep.Kind.SIGNED, done = signedAt != null || status == ClaimStatus.SIGNED))
  }
}
