package com.syncstream

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.syncstream.ui.HomeScreen
import com.syncstream.ui.client.ClientScreen
import com.syncstream.ui.client.ClientViewModel
import com.syncstream.ui.client.JoinCodeScreen
import com.syncstream.ui.client.RoomTarget
import com.syncstream.ui.client.ScanScreen
import com.syncstream.ui.master.HostPickScreen
import com.syncstream.ui.master.MasterPlayerScreen
import com.syncstream.ui.master.MasterViewModel
import com.syncstream.ui.theme.SyncStreamTheme

/**
 * Single-activity host. Owns nothing but navigation; master state lives in
 * [com.syncstream.service.MasterStreamingService] (bound by [MasterViewModel]).
 *
 * Routes:
 * ```
 * home                                   HomeScreen (start; inset-padded)
 * masterGraph                            nested graph; MasterViewModel scoped to it
 *   hostPick                             HostPickScreen (inset-padded)
 *   masterPlayer                         MasterPlayerScreen (edge-to-edge)
 * guestGraph                             nested graph; ClientViewModel scoped to it
 *   joinCode?host={host}&port={port}&label={label}   JoinCodeScreen (inset-padded; graph start)
 *   scan                                 ScanScreen (edge-to-edge)
 *   watch                                ClientScreen (edge-to-edge)
 * ```
 * Home's "nearby room" navigates straight to the nested `joinCode?host=...` destination (that adds
 * the `guestGraph` entry to the back stack, so the shared ViewModel is created with it); Enter
 * code uses `joinCode` with no args. Every End room / Leave pops to `home`, which clears the
 * graph's ViewModel (and tears down the room or the connection). End room stops streaming first.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SyncStreamTheme {
                SyncStreamNavHost()
            }
        }
    }
}

private const val JOIN_CODE_ROUTE = "joinCode?host={host}&port={port}&label={label}"

private fun joinCodeRoute(room: RoomTarget) =
    "joinCode?host=${Uri.encode(room.host)}&port=${room.port}&label=${Uri.encode(room.label)}"

private fun NavController.popToHome() {
    popBackStack("home", inclusive = false)
}

private fun NavController.navigateToWatch() {
    navigate("watch") { popUpTo("guestGraph") { inclusive = false } }
}

@Composable
private fun SyncStreamNavHost() {
    val navController = rememberNavController()

    @Composable
    fun masterVm(entry: NavBackStackEntry): MasterViewModel {
        val parent = remember(entry) { navController.getBackStackEntry("masterGraph") }
        return viewModel(parent)
    }

    @Composable
    fun guestVm(entry: NavBackStackEntry): ClientViewModel {
        val parent = remember(entry) { navController.getBackStackEntry("guestGraph") }
        return viewModel(parent)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize(),
        ) {
            composable("home") {
                Box(modifier = Modifier.padding(innerPadding)) {
                    HomeScreen(
                        onHost = { navController.navigate("masterGraph") },
                        onJoinRoom = { navController.navigate(joinCodeRoute(it)) },
                        onScan = { navController.navigate("scan") },
                        onEnterCode = { navController.navigate("joinCode") },
                    )
                }
            }

            navigation(startDestination = "hostPick", route = "masterGraph") {
                composable("hostPick") { entry ->
                    val vm = masterVm(entry)
                    Box(modifier = Modifier.padding(innerPadding)) {
                        HostPickScreen(
                            viewModel = vm,
                            onVideoChosen = { navController.navigate("masterPlayer") },
                            onEndRoom = { vm.stopStreaming(); navController.popToHome() },
                        )
                    }
                }
                composable("masterPlayer") { entry ->
                    val vm = masterVm(entry)
                    MasterPlayerScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                        onEndRoom = { vm.stopStreaming(); navController.popToHome() },
                    )
                }
            }

            navigation(startDestination = JOIN_CODE_ROUTE, route = "guestGraph") {
                composable(
                    route = JOIN_CODE_ROUTE,
                    arguments = listOf(
                        navArgument("host") { type = NavType.StringType; nullable = true; defaultValue = null },
                        navArgument("port") { type = NavType.IntType; defaultValue = -1 },
                        navArgument("label") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    val vm = guestVm(entry)
                    val host = entry.arguments?.getString("host")
                    val port = entry.arguments?.getInt("port") ?: -1
                    val label = entry.arguments?.getString("label")
                    val room = if (host != null && port >= 0) RoomTarget(host, port, label ?: host) else null
                    Box(modifier = Modifier.padding(innerPadding)) {
                        JoinCodeScreen(
                            viewModel = vm,
                            room = room,
                            onJoined = { navController.navigateToWatch() },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable("scan") { entry ->
                    ScanScreen(
                        viewModel = guestVm(entry),
                        onJoined = { navController.navigateToWatch() },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("watch") { entry ->
                    ClientScreen(
                        viewModel = guestVm(entry),
                        onLeave = { navController.popToHome() },
                    )
                }
            }
        }
    }
}
