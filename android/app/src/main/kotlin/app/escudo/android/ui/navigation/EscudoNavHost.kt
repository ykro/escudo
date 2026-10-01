package app.escudo.android.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import app.escudo.android.BuildConfig
import app.escudo.android.assistant.VoiceFixtures
import app.escudo.android.assistant.VoiceRequest
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
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
import app.escudo.android.ui.theme.Spacing
import kotlin.reflect.KClass
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable data object PolicyRoute
@Serializable data object ClaimRoute
@Serializable data class ApprovalsRoute(val highlightId: String? = null)
@Serializable data object AssistantRoute
@Serializable data object ReportRoute

private enum class Tab(val route: Any, val routeClass: KClass<*>, @StringRes val label: Int, @DrawableRes val icon: Int) {
  POLICY(PolicyRoute, PolicyRoute::class, R.string.nav_policy, R.drawable.ic_tab_policy),
  CLAIM(ClaimRoute, ClaimRoute::class, R.string.nav_claim, R.drawable.ic_tab_claim),
  APPROVALS(ApprovalsRoute(), ApprovalsRoute::class, R.string.nav_approvals, R.drawable.ic_tab_approvals),
  ASSISTANT(AssistantRoute, AssistantRoute::class, R.string.nav_assistant, R.drawable.ic_tab_assistant),
}

/**
 * @param deepLinkApprovalId set when the app was opened from an approval push; the host
 * navigates to the approvals screen and highlights that request, then calls [onDeepLinkHandled].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EscudoNavHost(
  container: AppContainer,
  deepLinkApprovalId: String?,
  deepLinkNonce: Int,
  onDeepLinkHandled: () -> Unit,
  debugVoice: VoiceRequest? = null,
  onDebugVoiceHandled: () -> Unit = {},
) {
  val nav = rememberNavController()
  val snackbar = remember { SnackbarHostState() }
  val backStack by nav.currentBackStackEntryAsState()
  val destination = backStack?.destination
  val onReport = destination?.hasRoute(ReportRoute::class) == true
  var pendingApprovals by remember { mutableIntStateOf(0) }
  // Debug only: a recorded voice to play into the assistant, from the intent or the hidden picker.
  var voiceForAssistant by remember { mutableStateOf<VoiceRequest?>(null) }
  var showVoicePicker by remember { mutableStateOf(false) }

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
  LaunchedEffect(debugVoice) {
    if (debugVoice != null) {
      voiceForAssistant = debugVoice
      openTab(AssistantRoute)
      onDebugVoiceHandled()
    }
  }
  // Badge on the Approvals tab: the agent can ask at any moment, whatever screen is open.
  LaunchedEffect(Unit) {
    while (true) {
      runCatching { container.repository.pendingApprovals().size }.onSuccess { pendingApprovals = it }
      delay(4_000)
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      CenterAlignedTopAppBar(
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        title = {
          val onAssistant = destination?.hasRoute(AssistantRoute::class) == true
          Box(
            if (BuildConfig.DEBUG && onAssistant) {
              // Hidden developer toggle: long-press the title to play a voice fixture.
              Modifier.combinedClickable(onClick = {}, onLongClick = { showVoicePicker = true })
            } else {
              Modifier
            },
          ) { TopBarTitle(destination) }
        },
        navigationIcon = {
          if (onReport) {
            IconButton(onClick = { nav.popBackStack() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
          }
        },
      )
    },
    bottomBar = {
      if (!onReport) {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
          Tab.entries.forEach { tab ->
            NavigationBarItem(
              selected = destination?.hasRoute(tab.routeClass) == true,
              onClick = { openTab(tab.route) },
              icon = {
                BadgedBox(badge = {
                  if (tab == Tab.APPROVALS && pendingApprovals > 0) Badge { Text(pendingApprovals.toString()) }
                }) {
                  Icon(painterResource(tab.icon), contentDescription = null)
                }
              },
              label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium) },
              colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
              ),
            )
          }
        }
      }
    },
  ) { padding ->
    if (showVoicePicker) {
      VoicePickerDialog(
        onPick = { voiceForAssistant = it; showVoicePicker = false },
        onDismiss = { showVoicePicker = false },
      )
    }
    NavHost(nav, startDestination = PolicyRoute, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
      composable<PolicyRoute> { PolicyScreen(container.repository, onReportAccident = { nav.navigate(ReportRoute) }) }
      composable<ClaimRoute> {
        ClaimStatusScreen(
          container.repository,
          onReportAccident = { nav.navigate(ReportRoute) },
          onOpenApprovals = { openTab(ApprovalsRoute()) },
        )
      }
      composable<ApprovalsRoute> { entry ->
        ApprovalsScreen(container, highlightId = entry.toRoute<ApprovalsRoute>().highlightId, snackbar = snackbar)
      }
      composable<AssistantRoute> {
        AssistantScreen(
          container.assistant,
          pendingApprovals,
          onOpenApprovals = { openTab(ApprovalsRoute(it)) },
          voiceRequest = voiceForAssistant,
          onVoiceConsumed = { voiceForAssistant = null },
        )
      }
      composable<ReportRoute> {
        ReportAccidentScreen(container, onSeeClaim = { nav.popBackStack(); openTab(ClaimRoute) })
      }
    }
  }
}

/** Debug-only list of bundled voice fixtures (testdata/voice). */
@Composable
private fun VoicePickerDialog(onPick: (VoiceRequest) -> Unit, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val names = remember { VoiceFixtures.bundled(context) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.debug_voice_title)) },
    text = {
      Column {
        if (names.isEmpty()) Text(stringResource(R.string.debug_voice_empty))
        names.forEach { name ->
          TextButton(onClick = { onPick(VoiceFixtures.resolve(context, name)) }) { Text(name) }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.debug_voice_close)) } },
  )
}

@Composable
private fun TopBarTitle(destination: NavDestination?) {
  val title = when {
    destination == null || destination.hasRoute(PolicyRoute::class) -> null
    destination.hasRoute(ClaimRoute::class) -> R.string.title_claim
    destination.hasRoute(ApprovalsRoute::class) -> R.string.title_approvals
    destination.hasRoute(AssistantRoute::class) -> R.string.title_assistant
    destination.hasRoute(ReportRoute::class) -> R.string.report_title
    else -> null
  }
  if (title != null) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
  } else {
    // Home: the brand, mark + name.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
      Icon(painterResource(R.drawable.ic_escudo_mark), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
      Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
    }
  }
}
