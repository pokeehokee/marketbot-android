package com.marketbot.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("home", "Home", Icons.Outlined.Dashboard),
    Tab("strategies", "Strategies", Icons.Outlined.Tune),
    Tab("activity", "Activity", Icons.Outlined.ListAlt),
    Tab("accounts", "Accounts", Icons.Outlined.AccountBalanceWallet),
    Tab("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    val toast by vm.toast.collectAsState()
    LaunchedEffect(toast) { toast?.let { snack.showSnackbar(it); vm.toastShown() } }
    val ctx = LocalContext.current
    var setupDone by remember { mutableStateOf(Setup.done(ctx)) }
    var goAccounts by remember { mutableStateOf(false) }
    if (!setupDone) { SetupWizard(vm) { keys -> setupDone = true; goAccounts = keys }; return }
    // After the graph exists: navigating in the frame that first composes the NavHost would fail.
    LaunchedEffect(goAccounts) { if (goAccounts) { nav.navigate("accounts"); goAccounts = false } }
    // Launcher shortcuts: each lands on a screen and, for anything that acts, asks first.
    val shortcut by com.marketbot.app.Shortcuts.requested.collectAsState()
    var askStop by remember { mutableStateOf(false) }
    LaunchedEffect(shortcut) {
        when (shortcut) {
            "kill" -> { nav.navigate("home") { popUpTo("home") { inclusive = false }; launchSingleTop = true }; vm.askKill() }
            "trades" -> nav.navigate("activity?q=&level=TRADE") { launchSingleTop = true }
            "stop" -> askStop = true
        }
        if (shortcut != null) com.marketbot.app.Shortcuts.requested.value = null
    }
    if (askStop) AlertDialog(onDismissRequest = { askStop = false },
        confirmButton = { Button({ askStop = false; vm.stopService() }) { Text(androidx.compose.ui.res.stringResource(com.marketbot.app.R.string.stop_engine)) } },
        dismissButton = { androidx.compose.material3.TextButton({ askStop = false }) { Text(androidx.compose.ui.res.stringResource(com.marketbot.app.R.string.back)) } },
        title = { Text(androidx.compose.ui.res.stringResource(com.marketbot.app.R.string.stop_the_engine)) },
        text = { Text(androidx.compose.ui.res.stringResource(com.marketbot.app.R.string.stop_the_engine_text)) })

    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val height = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    // A Flip's cover screen (or any window this small): only what matters at a glance.
    if (width < 480 && height < 480) { Scaffold(snackbarHost = { SnackbarHost(snack) }) { p -> CoverScreen(vm, Modifier.padding(p)) }; return }
    val rail = width >= 600          // tablets, foldables open, phones in landscape
    val twoPane = width >= 840       // room for the strategy list beside its detail
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    fun selected(t: Tab) = route == t.route || route?.startsWith(t.route + "?") == true ||
        (t.route == "strategies" && (route?.startsWith("strategy/") == true || route?.startsWith("help/") == true))
    // At large font sizes five labels cannot fit: show only the selected one, never a word broken in two.
    val bigText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    fun go(t: Tab) = nav.navigate(t.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (!rail) NavigationBar {
                tabs.forEach { t -> NavigationBarItem(selected = selected(t), onClick = { go(t) }, icon = { Icon(t.icon, t.label) },
                    label = if (bigText) null else ({ Text(t.label, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })) }
            }
        },
    ) { pad ->
        androidx.compose.foundation.layout.Row(Modifier.padding(pad)) {
        if (rail) androidx.compose.material3.NavigationRail {
            tabs.forEach { t -> androidx.compose.material3.NavigationRailItem(selected = selected(t), onClick = { go(t) }, icon = { Icon(t.icon, null) }, label = { Text(t.label) }) }
        }
        Column(Modifier.weight(1f)) {
        SystemBanners(vm)
        NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
            composable("home") { HomeScreen(vm, onOpen = { nav.navigate("strategy/$it") }, onSources = { nav.navigate("sources") }) }
            composable("strategies") {
                if (twoPane) StrategiesTwoPane(vm) else
                StrategiesScreen(vm, onGoLive = { nav.navigate("strategy/$it?tab=2") }) { nav.navigate("strategy/$it") }
            }
            composable("strategy/{id}?tab={tab}", arguments = listOf(navArgument("id") { type = NavType.StringType },
                navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { e ->
                StrategyScreen(vm, e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }, initialTab = e.arguments!!.getInt("tab"),
                    onHelp = { nav.navigate("help/$it") })
            }
            composable("activity?q={q}&level={level}", arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" },
                navArgument("level") { type = NavType.StringType; defaultValue = "" })) { e ->
                ActivityScreen(vm, e.arguments?.getString("q").orEmpty(), e.arguments?.getString("level")?.ifBlank { null })
            }
            composable("help/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
                HelpScreen(vm, e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() })
            }
            composable("accounts") { AccountsScreen(vm) }
            composable("settings") { SettingsScreen(vm, onSources = { nav.navigate("sources") }) }
            composable("sources") { SourcesScreen(vm, onBack = { nav.popBackStack() }, onFind = { q -> nav.navigate("activity?q=${android.net.Uri.encode(q)}") }) }
        }
        }
        }
    }
}

/** Wide screens: the strategy list on the left, the chosen strategy on the right. */
@Composable
private fun StrategiesTwoPane(vm: MainViewModel) {
    var pick by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(vm.strategies.first().id) }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    androidx.compose.foundation.layout.Row {
        Column(Modifier.weight(0.42f)) {
            StrategiesScreen(vm, onGoLive = { pick = it; tab = 2 }) { pick = it; tab = 0 }
        }
        androidx.compose.material3.VerticalDivider()
        Column(Modifier.weight(0.58f)) {
            androidx.compose.runtime.key(pick, tab) { StrategyScreen(vm, pick, onBack = {}, initialTab = tab, showBack = false) }
        }
    }
}

/**
 * The whole app on a cover screen: whether the engine is alive, today's P&L and
 * the kill switch. Everything else waits for the main screen.
 */
@Composable
fun CoverScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val killed by vm.killed.collectAsState()
    val running by vm.engineRunning.collectAsState()
    val p by vm.portfolio.collectAsState()
    val busy by vm.inFlight.collectAsState()
    val status by vm.status.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val now = LocalNow.current
    val beat = com.marketbot.app.WidgetBeat.of(running, com.marketbot.app.EngineService.wasRunning(ctx), com.marketbot.app.Health.lastBeat(ctx), now.toEpochMilli())
    Column(modifier.verticalScroll(rememberScrollState()).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(Radius.pill).background(androidx.compose.ui.graphics.Color(beat.color)))
            androidx.compose.foundation.layout.Spacer(Modifier.width(Space.s))
            Text(if (killed) "Kill switch ON" else beat.text, style = MaterialTheme.typography.titleSmall)
        }
        androidx.compose.foundation.layout.Row {
            Column(Modifier.weight(1f)) {
                Text("Live today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyTicker(p.liveToday, Figures, moneyColor(p.liveToday) ?: MaterialTheme.colorScheme.onSurface, announce = true)
            }
            Column(Modifier.weight(1f)) {
                Text("Paper today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyTicker(p.paperToday, Figures, moneyColor(p.paperToday) ?: MaterialTheme.colorScheme.onSurface)
            }
        }
        status.values.filter { it.running }.mapNotNull { it.nextRunAt }.minOrNull()?.let {
            Text("Next pass ${it.atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (killed) Text("Live trading is off. Turn the switch off on the main screen.", style = MaterialTheme.typography.bodySmall, color = LocalDesk.current.loss)
        else HoldToConfirm(androidx.compose.ui.res.stringResource(com.marketbot.app.R.string.kill), LocalDesk.current.loss, MaterialTheme.colorScheme.onError,
            "kill" !in busy, androidx.compose.ui.res.stringResource(com.marketbot.app.R.string.kill_a11y), onConfirm = { vm.kill(true) }, a11yAction = { confirm = true },
            modifier = Modifier.fillMaxWidth())
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        confirmButton = { Button({ confirm = false; vm.kill(true) }) { Text(androidx.compose.ui.res.stringResource(com.marketbot.app.R.string.cancel_everything)) } },
        dismissButton = { androidx.compose.material3.TextButton({ confirm = false }) { Text(androidx.compose.ui.res.stringResource(com.marketbot.app.R.string.back)) } },
        title = { Text(androidx.compose.ui.res.stringResource(com.marketbot.app.R.string.turn_on_the_kill_switch)) })
}
