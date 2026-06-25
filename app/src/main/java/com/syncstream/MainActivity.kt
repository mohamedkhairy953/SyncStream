package com.syncstream

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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.syncstream.ui.RolePickerScreen
import com.syncstream.ui.client.ClientScreen
import com.syncstream.ui.client.QrScanScreen
import com.syncstream.ui.master.MasterPlayerScreen
import com.syncstream.ui.master.MasterScreen
import com.syncstream.ui.master.MasterViewModel
import com.syncstream.ui.theme.SyncStreamTheme

/**
 * Single-activity host. Owns nothing but navigation; master state lives in
 * [com.syncstream.service.MasterStreamingService] (bound by [MasterScreen]).
 *
 * Routes:
 *   "rolePicker"            -> [RolePickerScreen]
 *   "masterGraph"           -> nested graph
 *     "master"              -> [MasterScreen]
 *     "masterPlayer"        -> [MasterPlayerScreen]  (edge-to-edge, no inset padding)
 *   "scan"                  -> [QrScanScreen]
 *   "client/{host}/{port}"  -> [ClientScreen]
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

@Composable
private fun SyncStreamNavHost() {
    val navController = rememberNavController()
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "rolePicker",
            modifier = Modifier.fillMaxSize(),
        ) {
            composable("rolePicker") {
                Box(modifier = Modifier.padding(innerPadding)) {
                    RolePickerScreen(
                        onPickMaster = { navController.navigate("master") },
                        onPickClient = { navController.navigate("scan") },
                    )
                }
            }

            navigation(startDestination = "master", route = "masterGraph") {
                composable("master") {
                    val parent = remember(it) { navController.getBackStackEntry("masterGraph") }
                    val vm: MasterViewModel = viewModel(parent)
                    Box(modifier = Modifier.padding(innerPadding)) {
                        MasterScreen(
                            viewModel = vm,
                            onStartStreaming = { navController.navigate("masterPlayer") },
                            onExit = { vm.stopStreaming(); navController.popBackStack("rolePicker", false) },
                        )
                    }
                }
                composable("masterPlayer") {
                    val parent = remember(it) { navController.getBackStackEntry("masterGraph") }
                    val vm: MasterViewModel = viewModel(parent)
                    MasterPlayerScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                        onStopHosting = { vm.stopStreaming(); navController.popBackStack("rolePicker", false) },
                    )
                }
            }

            composable("scan") {
                Box(modifier = Modifier.padding(innerPadding)) {
                    QrScanScreen(
                        onScanned = { host, port ->
                            navController.navigate("client/$host/$port")
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable(
                route = "client/{host}/{port}",
                arguments = listOf(
                    navArgument("host") { type = NavType.StringType },
                    navArgument("port") { type = NavType.IntType },
                ),
            ) { backStackEntry ->
                val host = backStackEntry.arguments?.getString("host").orEmpty()
                val port = backStackEntry.arguments?.getInt("port") ?: 0
                Box(modifier = Modifier.padding(innerPadding)) {
                    ClientScreen(
                        host = host,
                        port = port,
                        onExit = { navController.popBackStack("scan", inclusive = false) },
                    )
                }
            }
        }
    }
}
