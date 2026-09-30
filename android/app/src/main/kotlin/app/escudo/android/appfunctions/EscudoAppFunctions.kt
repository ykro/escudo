package app.escudo.android.appfunctions

import android.net.Uri
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import app.escudo.android.container
import app.escudo.android.data.api.ApiException
import app.escudo.android.data.events.Surface
import app.escudo.android.data.events.TimelineEvents
import kotlinx.coroutines.CancellationException

/**
 * Escudo's capabilities for on-device agents (Gemini, or the AppFunctions developer tools).
 *
 * Three read/report functions, all "automatic" in the autonomy table. Nothing here can accept
 * the deductible, book a shop, sign a claim or approve anything: those need a person.
 * Every function goes through the same [app.escudo.android.data.EscudoRepository] as the screens.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
  serviceName = "EscudoAppFunctionService",
  appFunctionXmlFileName = "escudo_app_function_service",
)
abstract class BaseEscudoAppFunctionService : AppFunctionService() {

  private val repository get() = container.repository
  private val events get() = container.events

  /**
   * Reports a car accident to Escudo: opens an insurance claim and, when photos are given,
   * attaches them so the damage is assessed (parts, severity, cost range).
   * Use it when the user says they had a crash or accident with their insured car.
   *
   * @param description What happened, in the user's words. Required; ask the user if missing.
   * @param photoUris Content URIs of photos of the damage (up to 5) shared by the user.
   * @param recentPhotoCount If no URIs are given, attach this many of the newest gallery photos (1 to 5).
   * @param locationLabel Where it happened, e.g. "Calzada Roosevelt, zona 11".
   * @param occurredAt When it happened, ISO-8601. Defaults to now.
   * @return The new claim id and the damage assessment.
   * @throws AppFunctionInvalidArgumentException If the description is empty or a parameter is out of range. Ask the user to fix it.
   * @throws AppFunctionAppUnknownException If Escudo could not be reached. Suggest trying again.
   */
  @AppFunction(isDescribedByKDoc = true)
  suspend fun reportAccident(
    description: String,
    photoUris: List<Uri>? = null,
    recentPhotoCount: Int? = null,
    locationLabel: String? = null,
    occurredAt: String? = null,
  ): ReportAccidentResult = agentCall("open_claim") {
    val input = AppFunctionMapping.reportAccidentInput(
      description, photoUris?.map(Uri::toString), recentPhotoCount, locationLabel, occurredAt,
    )
    val uris = when (val selection = input.photos) {
      PhotoSelection.None -> emptyList()
      is PhotoSelection.Uris -> selection.uris.map(Uri::parse)
      is PhotoSelection.MostRecent -> try {
        container.photos.mostRecent(selection.count)
      } catch (e: SecurityException) {
        throw AppFunctionInvalidArgumentException(
          "Escudo cannot read the gallery yet. Ask the user to open Escudo once and allow photo access, or share the photo URIs.",
        )
      }
    }
    val photos = container.photos.load(uris)
    val report = repository.reportAccident(input.description, photos, input.location, input.occurredAt, Surface.APP_FUNCTIONS)
    AppFunctionMapping.reportAccident(report, photos.size)
  }

  /**
   * Returns the user's car insurance policy: coverages, deductible, vehicle and replacement-car days.
   * If an incident type is given, also says whether that incident is covered.
   *
   * @param incidentType Optional incident to check, e.g. "collision", "theft", "towing".
   * @return The policy summary and, if requested, the coverage decision.
   */
  @AppFunction(isDescribedByKDoc = true)
  suspend fun getCoverage(incidentType: String? = null): CoverageResult = agentCall("get_policy") {
    val policy = repository.policy()
    val type = AppFunctionMapping.incidentType(incidentType)
    val check = type?.let { repository.checkCoverage(it) }
    AppFunctionMapping.coverage(policy, type, check)
  }

  /**
   * Returns the status of an insurance claim: damage assessment, tow, repair shop, deductible and
   * signature. Without a claim id, returns the user's most recent claim.
   *
   * @param claimId Optional claim id (as returned by reportAccident).
   * @return The claim status and the steps completed so far.
   * @throws AppFunctionElementNotFoundException If the user has no claims or the id does not exist.
   */
  @AppFunction(isDescribedByKDoc = true)
  suspend fun getClaimStatus(claimId: String? = null): ClaimStatusResult = agentCall("get_claim_status") {
    val claim = try {
      repository.claim(AppFunctionMapping.claimId(claimId))
    } catch (e: ApiException) {
      if (e.code == 404) null else throw e
    } ?: throw AppFunctionElementNotFoundException(
      if (claimId.isNullOrBlank()) "The user has no claims yet." else "No claim with id $claimId.",
    )
    AppFunctionMapping.claimStatus(claim)
  }

  /** Timeline event + uniform error translation for every agent call. */
  private suspend fun <T> agentCall(tool: String, block: suspend () -> T): T {
    if (tool != "open_claim") {
      // reportAccident posts its own finer-grained events from the repository.
      events.post(Surface.APP_FUNCTIONS, TimelineEvents.Kind.TOOL_CALL, "Agente llamó a $tool vía AppFunctions", tool = tool, autonomy = "automatic")
    }
    return try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: InvalidAppFunctionInput) {
      throw AppFunctionInvalidArgumentException(e.message ?: "invalid input")
    } catch (e: AppFunctionInvalidArgumentException) {
      throw e
    } catch (e: AppFunctionElementNotFoundException) {
      throw e
    } catch (e: Exception) {
      events.post(Surface.APP_FUNCTIONS, TimelineEvents.Kind.ERROR, "Error en $tool: ${e.message}", tool = tool)
      throw AppFunctionAppUnknownException("Escudo could not complete $tool: ${e.message}")
    }
  }
}
