package app.escudo.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.escudo.android.R
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val UiLocale: Locale = Locale.forLanguageTag("es-GT")

fun formatUsd(amount: Double): String =
  NumberFormat.getNumberInstance(UiLocale).apply { maximumFractionDigits = 0 }.format(amount)

private val dateTimeFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm", UiLocale)

/** ISO-8601 → "30 sep, 14:05" in the phone's time zone; raw text if it does not parse. */
fun formatDateTime(iso: String?): String? {
  if (iso.isNullOrBlank()) return null
  val instant = runCatching { Instant.parse(iso) }.recoverCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
    ?: return iso
  return dateTimeFormat.format(instant.atZone(ZoneId.systemDefault()))
}

@Composable
fun coverageLabel(code: String): String = when (code) {
  "own_damage" -> stringResource(R.string.coverage_own_damage)
  "third_party" -> stringResource(R.string.coverage_third_party)
  "towing" -> stringResource(R.string.coverage_towing)
  "rental_car" -> stringResource(R.string.coverage_rental_car)
  "theft" -> stringResource(R.string.coverage_theft)
  else -> code
}

@Composable
fun severityLabel(code: String?): String = when (code) {
  "minor" -> stringResource(R.string.severity_minor)
  "moderate" -> stringResource(R.string.severity_moderate)
  "severe" -> stringResource(R.string.severity_severe)
  else -> code ?: "—"
}

@Composable
fun toolLabel(tool: String): String = when (tool) {
  "accept_deductible" -> stringResource(R.string.tool_accept_deductible)
  "select_repair_shop" -> stringResource(R.string.tool_select_repair_shop)
  "sign_claim" -> stringResource(R.string.tool_sign_claim)
  "request_rental_car" -> stringResource(R.string.tool_request_rental_car)
  else -> tool
}
