package tech.mmarca.openwatch.navigation

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import tech.mmarca.openwatch.features.connectedapps.ConnectedAppsRoute

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.presentation.UnitFormatter
import tech.mmarca.openwatch.features.healthconnect.HealthConnectSetupRoute
import tech.mmarca.openwatch.features.home.HomeScreen
import tech.mmarca.openwatch.features.scales.ScaleDeviceScreen
import tech.mmarca.openwatch.features.settings.BodyProfileRoute
import tech.mmarca.openwatch.features.settings.SettingsRoute
import tech.mmarca.openwatch.ui.components.LocalAppBarState
import tech.mmarca.openwatch.ui.components.LocalSnackbarHostState

/**
 * One app bar for every screen: the route's title, or what the screen declared
 * through [LocalAppBarState], and a back arrow everywhere but home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    unitFormatter: UnitFormatter,
    startDestination: String,
    externalNavigationRoute: String? = null,
    onExternalNavigationHandled: () -> Unit = {},
    onSetupComplete: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    val appBarState = LocalAppBarState.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val declared = appBarState.of(backStackEntry)
    val routeTitle = Screen.titleResFor(currentRoute?.substringBefore('?'))?.let { stringResource(it) }
    val title = declared?.title ?: routeTitle ?: stringResource(R.string.app_name)
    // The two roots: nothing to go back to.
    val isHome = currentRoute == Screen.Home.route || currentRoute == Screen.Onboarding.route
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(externalNavigationRoute, currentRoute) {
        if (externalNavigationRoute != null && currentRoute != null) {
            navController.navigate(externalNavigationRoute) { launchSingleTop = true }
            onExternalNavigationHandled()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (declared?.hidesAppBar != true) {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (!isHome) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                )
                            }
                        }
                    },
                    actions = {
                        declared?.actions?.forEach { action ->
                            IconButton(onClick = action.onClick) {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = stringResource(action.contentDescription),
                                    tint = action.tint ?: MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = declared?.containerColor ?: MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                composable(Screen.Onboarding.route) {
                    HealthConnectSetupRoute(
                        onContinue = {
                            onSetupComplete()
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Screen.HealthConnectSetup.route) {
                    HealthConnectSetupRoute(onContinue = null)
                }
                composable(Screen.Home.route) {
                    HomeScreen(
                        watchesViewModel = hiltViewModel(),
                        scalesViewModel = hiltViewModel(),
                        homeViewModel = hiltViewModel(),
                        onOpenWatch = { deviceId ->
                            navController.navigate(Screen.WatchDevice.createRoute(deviceId)) { launchSingleTop = true }
                        },
                        onOpenScale = { navController.navigate(Screen.ScaleDevice.route) { launchSingleTop = true } },
                        onOpenHealthConnect = {
                            navController.navigate(Screen.HealthConnectSetup.route) { launchSingleTop = true }
                        },
                        onOpenSettings = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } },
                    )
                }
                composable(Screen.Settings.route) {
                    SettingsRoute(
                        onOpenHealthConnect = {
                            navController.navigate(Screen.HealthConnectSetup.route) { launchSingleTop = true }
                        },
                        onOpenBodyProfile = { navController.navigate(Screen.BodyProfile.route) { launchSingleTop = true } },
                        onOpenConnectedApps = { navController.navigate(Screen.ConnectedApps.route) { launchSingleTop = true } },
                    )
                }
                composable(Screen.ConnectedApps.route) {
                    ConnectedAppsRoute()
                }
                composable(Screen.BodyProfile.route) {
                    BodyProfileRoute(unitFormatter = unitFormatter)
                }
                composable(Screen.ScaleDevice.route) {
                    ScaleDeviceScreen(
                        viewModel = hiltViewModel(),
                        onOpenBodyProfile = { navController.navigate(Screen.BodyProfile.route) { launchSingleTop = true } },
                        onRemoved = { navController.popBackStack() },
                    )
                }
                watchRoutes(navController)
            }
        }
    }
}
