package app.escudo.android.push

/**
 * An approval request as it arrives in an FCM push.
 *
 * Payload sent by backend/escudo/push.py (see contracts/CHANGELOG.md): a notification block
 * (title, body = summary) plus data `{"type": "approval_request", "approval_id", "tool", "claim_id", "reason"}`.
 */
data class ApprovalPush(val approvalId: String, val title: String?, val body: String?) {
  companion object {
    private val TYPES = setOf("approval_request", "approval_requested")

    fun parse(data: Map<String, String>, title: String?, body: String?): ApprovalPush? {
      val type = data["type"]
      if (type != null && type !in TYPES) return null
      val id = data["approval_id"]?.takeIf { it.isNotBlank() } ?: return null
      return ApprovalPush(
        approvalId = id,
        title = title ?: data["title"],
        body = body ?: data["summary"] ?: data["reason"],
      )
    }
  }
}
