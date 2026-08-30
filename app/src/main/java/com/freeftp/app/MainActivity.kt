package com.freeftp.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.freeftp.app.ui.BrowserScreen
import com.freeftp.app.ui.BrowserViewModel
import com.freeftp.app.ui.FileViewerScreen
import com.freeftp.app.ui.FileViewerViewModel
import com.freeftp.app.ui.FreeFtpTheme
import com.freeftp.app.ui.ServerEditScreen
import com.freeftp.app.ui.ServerEditViewModel
import com.freeftp.app.ui.ServerListScreen
import com.freeftp.app.ui.ServerListViewModel
import com.freeftp.app.ui.SettingsScreen
import com.freeftp.app.ui.TransfersScreen

private object Routes {
    const val SERVERS = "servers"
    const val EDIT = "servers/edit"
    const val BROWSE = "browse"
    const val TRANSFERS = "transfers"
    const val VIEWER = "viewer"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeFtpTheme {
                FreeFtpNavHost(rememberNavController(), applicationContext.appContainer)
            }
        }
    }
}

@Composable
private fun FreeFtpNavHost(navController: NavHostController, container: AppContainer) {
    NavHost(navController = navController, startDestination = Routes.SERVERS) {
        composable(Routes.SERVERS) {
            val viewModel: ServerListViewModel =
                viewModel(factory = ServerListViewModel.Factory(container))
            // Returning from the editor must show the change that was just saved.
            androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }
            ServerListScreen(
                viewModel = viewModel,
                onAddServer = { navController.navigate(Routes.EDIT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onEditServer = { navController.navigate("${Routes.EDIT}?id=$it") },
                onConnected = { navController.navigate(Routes.BROWSE) },
            )
        }

        composable("${Routes.EDIT}?id={id}") { entry ->
            val id = entry.arguments?.getString("id")
            ServerEditScreen(
                viewModel = viewModel(
                    key = "edit-${id ?: "new"}",
                    factory = ServerEditViewModel.Factory(container, id),
                ),
                onDone = { navController.popBackStack() },
            )
        }

        composable(Routes.EDIT) {
            ServerEditScreen(
                viewModel = viewModel(key = "edit-new", factory = ServerEditViewModel.Factory(container, null)),
                onDone = { navController.popBackStack() },
            )
        }

        composable(Routes.BROWSE) {
            BrowserScreen(
                viewModel = viewModel(factory = BrowserViewModel.Factory(container)),
                onDisconnected = {
                    navController.popBackStack(Routes.SERVERS, inclusive = false)
                },
                onOpenTransfers = { navController.navigate(Routes.TRANSFERS) },
                // Remote paths contain slashes and spaces, so they travel encoded.
                onViewFile = { path ->
                    navController.navigate("${Routes.VIEWER}?path=${Uri.encode(path)}")
                },
            )
        }

        composable("${Routes.VIEWER}?path={path}") { entry ->
            val path = entry.arguments?.getString("path").orEmpty()
            FileViewerScreen(
                viewModel = viewModel(
                    key = "viewer-$path",
                    factory = FileViewerViewModel.Factory(container, path),
                ),
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.TRANSFERS) {
            val context = LocalContext.current
            TransfersScreen(
                manager = container.session.transfers,
                onBack = { navController.popBackStack() },
                onOpenFile = { status ->
                    openDownloadedFile(context, status)?.let { problem ->
                        Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                downloads = container.downloads,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
