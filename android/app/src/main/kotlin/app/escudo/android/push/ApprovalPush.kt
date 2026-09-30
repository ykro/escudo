package app.escudo.android.push

/**
 * An approval request as it arrives in an FCM push.
 *
 * Expected data payload (see contracts/CHANGELOG.md):
 * `{"type": "approval_requested", "approval_id": "apr_…", "tool": "sign_claim", "summary": "…"}`.
 * A notification block (title/body), if present, is used as the text.
 */
data class ApprovalPush(val approvalId: String, val title: String?, val body: String?) {
  companion object {
    fun parse(data: Map<String, String>, title: String?, body: String?): ApprovalPush? {
      val type = data["type"]
      if (type != null && type != "approval_requested") return null
      val id = data["approval_id"]?.takeIf { it.isNotBlank() } ?: return null
      return ApprovalPush(
        approvalId = id,
        title = title ?: data["title"],
        body = body ?: data["summary"] ?: data["reason"],
      )
    }
  }
}
