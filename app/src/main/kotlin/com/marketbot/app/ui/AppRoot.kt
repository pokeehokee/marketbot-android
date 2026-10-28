package com.marketbot.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            val entry by nav.currentBackStackEntryAsState()
            val route = entry?.destination?.route
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(selected = route == t.route || (t.route == "strategies" && route?.startsWith("strategy/") == true),
                        onClick = { nav.navigate(t.route) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
        SystemBanners(vm)
        NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
            composable("home") { HomeScreen(vm, onOpen = { nav.navigate("strategy/$it") }, onSources = { nav.navigate("sources") }) }
            composable("strategies") { StrategiesScreen(vm, onGoLive = { nav.navigate("strategy/$it?tab=2") }) { nav.navigate("strategy/$it") } }
            composable("strategy/{id}?tab={tab}", arguments = listOf(navArgument("id") { type = NavType.StringType },
                navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { e ->
                StrategyScreen(vm, e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }, initialTab = e.arguments!!.getInt("tab"))
            }
            composable("activity") { ActivityScreen(vm) }
            composable("accounts") { AccountsScreen(vm) }
            composable("settings") { SettingsScreen(vm, onSources = { nav.navigate("sources") }) }
            composable("sources") { SourcesScreen(vm, onBack = { nav.popBackStack() }) }
        }
        }
    }
}
