package app.escudo.android.ui.claim

import app.escudo.android.data.model.Claim
import app.escudo.android.data.model.ClaimStatus

/** One row of the claim timeline. [done] steps show their detail; the rest read "pending". */
data class TimelineStep(val kind: Kind, val done: Boolean) {
  enum class Kind { OPEN, ASSESSED, TOW, REPAIR, RENTAL, DEDUCTIBLE, SIGNED }
}

/**
 * Derives the timeline from the claim's own fields instead of trusting status order alone:
 * the agent may do tow, repair and deductible in any order.
 */
fun Claim.timeline(): List<TimelineStep> = buildList {
  add(TimelineStep(TimelineStep.Kind.OPEN, done = true))
  add(TimelineStep(TimelineStep.Kind.ASSESSED, done = assessment != null))
  add(TimelineStep(TimelineStep.Kind.TOW, done = tow?.provider != null))
  add(TimelineStep(TimelineStep.Kind.REPAIR, done = repair?.shopId != null || repair?.shopName != null))
  if (rental != null) add(TimelineStep(TimelineStep.Kind.RENTAL, done = true))
  add(TimelineStep(TimelineStep.Kind.DEDUCTIBLE, done = deductibleAccepted == true || status == ClaimStatus.DEDUCTIBLE_ACCEPTED))
  add(TimelineStep(TimelineStep.Kind.SIGNED, done = signedAt != null || status == ClaimStatus.SIGNED))
}
