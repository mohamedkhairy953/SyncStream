package com.syncstream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.syncstream.ui.RolePickerScreen
import com.syncstream.ui.client.ClientScreen
import com.syncstream.ui.client.DiscoveryScreen
import com.syncstream.ui.master.MasterScreen
import com.syncstream.ui.theme.SyncStreamTheme

/**
 * Single-activity host. Owns nothing but navigation; master state lives in
 * [com.syncstream.service.MasterStreamingService] (bound by [MasterScreen]).
 *
 * Routes:
 *   "rolePicker"            -> [RolePickerScreen]
 *   "master"                -> [MasterScreen]
 *   "discovery"             -> [DiscoveryScreen]
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable("rolePicker") {
                RolePickerScreen(
                    onPickMaster = { navController.navigate("master") },
                    onPickClient = { navController.navigate("discovery") },
                )
            }

            composable("master") {
                MasterScreen(
                    onExit = { navController.popBackStack() },
                )
            }

            composable("discovery") {
                DiscoveryScreen(
                    onConnect = { host, port ->
                        navController.navigate("client/$host/$port")
                    },
                    onBack = { navController.popBackStack() },
                )
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
                ClientScreen(
                    host = host,
                    port = port,
                    onExit = { navController.popBackStack("discovery", inclusive = false) },
                )
            }
        }
    }
}
