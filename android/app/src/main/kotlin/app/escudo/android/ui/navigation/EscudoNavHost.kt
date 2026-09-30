package app.escudo.android.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.escudo.android.AppContainer
import app.escudo.android.R
import app.escudo.android.ui.approvals.ApprovalsScreen
import app.escudo.android.ui.assistant.AssistantScreen
import app.escudo.android.ui.claim.ClaimStatusScreen
import app.escudo.android.ui.policy.PolicyScreen
import app.escudo.android.ui.report.ReportAccidentScreen
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

@Serializable data object PolicyRoute
@Serializable data object ClaimRoute
@Serializable data class ApprovalsRoute(val highlightId: String? = null)
@Serializable data object AssistantRoute
@Serializable data object ReportRoute

private enum class Tab(val route: Any, val routeClass: KClass<*>, @StringRes val label: Int, @DrawableRes val icon: Int) {
  POLICY(PolicyRoute, PolicyRoute::class, R.string.nav_policy, R.drawable.ic_shield),
  CLAIM(ClaimRoute, ClaimRoute::class, R.string.nav_claim, R.drawable.ic_claim),
  APPROVALS(ApprovalsRoute(), ApprovalsRoute::class, R.string.nav_approvals, R.drawable.ic_approval),
  ASSISTANT(AssistantRoute, AssistantRoute::class, R.string.nav_assistant, R.drawable.ic_mic),
}

/**
 * @param deepLinkApprovalId set when the app was opened from an approval push; the host
 * navigates to the approvals screen and highlights that request, then calls [onDeepLinkHandled].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EscudoNavHost(container: AppContainer, deepLinkApprovalId: String?, deepLinkNonce: Int, onDeepLinkHandled: () -> Unit) {
  val nav = rememberNavController()
  val snackbar = remember { SnackbarHostState() }
  val backStack by nav.currentBackStackEntryAsState()
  val destination = backStack?.destination
  val onReport = destination?.hasRoute(ReportRoute::class) == true

  fun openTab(route: Any) = nav.navigate(route) {
    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = route !is ApprovalsRoute
  }

  LaunchedEffect(deepLinkNonce) {
    if (deepLinkNonce > 0) {
      openTab(ApprovalsRoute(deepLinkApprovalId))
      onDeepLinkHandled()
    }
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      if (onReport) {
        TopAppBar(
          title = { Text(stringResource(R.string.report_title)) },
          navigationIcon = {
            IconButton(onClick = { nav.popBackStack() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
          },
        )
      }
    },
    bottomBar = {
      if (!onReport) {
        NavigationBar {
          Tab.entries.forEach { tab ->
            NavigationBarItem(
              selected = destination?.hasRoute(tab.routeClass) == true,
              onClick = { openTab(tab.route) },
              icon = { Icon(painterResource(tab.icon), contentDescription = null) },
              label = { Text(stringResource(tab.label)) },
            )
          }
        }
      }
    },
  ) { padding ->
    NavHost(nav, startDestination = PolicyRoute, modifier = Modifier.padding(padding)) {
      composable<PolicyRoute> { PolicyScreen(container.repository, onReportAccident = { nav.navigate(ReportRoute) }) }
      composable<ClaimRoute> { ClaimStatusScreen(container.repository, onReportAccident = { nav.navigate(ReportRoute) }) }
      composable<ApprovalsRoute> { entry ->
        ApprovalsScreen(container, highlightId = entry.toRoute<ApprovalsRoute>().highlightId, snackbar = snackbar)
      }
      composable<AssistantRoute> { AssistantScreen(container.assistant, onOpenApprovals = { openTab(ApprovalsRoute(it)) }) }
      composable<ReportRoute> {
        ReportAccidentScreen(container, onSeeClaim = { nav.popBackStack(); openTab(ClaimRoute) })
      }
    }
  }
}
