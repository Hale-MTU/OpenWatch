package tech.mmarca.openwatch.navigation

import android.content.Intent
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import tech.mmarca.openwatch.features.connectedapps.ConnectedAppsRoute

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tech.mmarca.openwatch.PermissionsRationaleActivity
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.presentation.UnitFormatter
import tech.mmarca.openwatch.features.healthconnect.HealthConnectSetupRoute
import tech.mmarca.openwatch.features.home.HomeDetailPlaceholder
import tech.mmarca.openwatch.features.home.HomeRoute
import tech.mmarca.openwatch.features.settings.BodyProfileRoute
import tech.mmarca.openwatch.features.settings.SettingsRoute
import tech.mmarca.openwatch.ui.components.LocalAppBarState
import tech.mmarca.openwatch.ui.components.LocalSnackbarHostState

/**
 * One app bar for every screen: the route's title, or what the screen declared
 * through [LocalAppBarState], and a back arrow everywhere but home. On a wide window
 * Home's list stays at the start side and what it opens shows beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    unitFormatter: UnitFormatter,
    startDestination: Any,
    externalNavigationRoute: Any? = null,
    onExternalNavigationHandled: () -> Unit = {},
    onSetupComplete: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    val appBarState = LocalAppBarState.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val declared = appBarState.of(backStackEntry)
    val routeTitle = destination?.titleRes()?.let { stringResource(it) }
    val title = declared?.title ?: routeTitle ?: stringResource(R.string.app_name)
    val isRoot = destination?.isRoot == true
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(externalNavigationRoute, destination) {
        if (externalNavigationRoute != null && destination != null) {
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
                        if (!isRoot) {
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
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                // The first run is a page of its own; after it, a wide window keeps Home's list
                // beside what it opens.
                val onboarding = destination?.hasRoute(Onboarding::class) ?: (startDestination == Onboarding)
                val listDetail = maxWidth >= ListDetailMinWidth && !onboarding
                val selectedDeviceId = backStackEntry?.deviceId()
                Row(modifier = Modifier.fillMaxSize()) {
                    if (listDetail) {
                        HomeList(
                            navController = navController,
                            opensBeside = true,
                            selectedDeviceId = selectedDeviceId,
                            modifier = Modifier
                                .width(ListPaneWidth)
                                .fillMaxHeight(),
                        )
                        VerticalDivider()
                    }
                    // Read inside the graph through a local, so the graph itself never changes with the window.
                    CompositionLocalProvider(LocalListDetail provides listDetail) {
                        AppNavHost(
                            navController = navController,
                            startDestination = startDestination,
                            unitFormatter = unitFormatter,
                            onSetupComplete = onSetupComplete,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

/** Whether Home's list is shown beside the screens, so the Home destination itself shows what nothing opened yet looks like. */
private val LocalListDetail = compositionLocalOf { false }

/** From this width (a tablet, an unfolded phone, a large phone on its side) the list and what it opens share the window. */
private val ListDetailMinWidth = 840.dp

private val ListPaneWidth = 360.dp

/**
 * Home's list, opening what is tapped. Beside the screens ([opensBeside]) it opens one thing
 * at a time: what was open beside it goes, so Back returns to the list's own state.
 */
@Composable
private fun HomeList(
    navController: NavHostController,
    opensBeside: Boolean,
    modifier: Modifier = Modifier,
    selectedDeviceId: String? = null,
) {
    val open: (Any) -> Unit = { route ->
        navController.navigate(route) {
            if (opensBeside) popUpTo<Home>()
            launchSingleTop = true
        }
    }
    HomeRoute(
        onOpenWatch = { deviceId -> open(DeviceDetail(deviceId)) },
        onOpenScale = { deviceId -> open(DeviceDetail(deviceId)) },
        onOpenDevice = { deviceId -> open(DeviceDetail(deviceId)) },
        onOpenHealthConnect = { open(HealthConnectSetup) },
        onOpenSettings = { open(Settings) },
        modifier = modifier,
        selectedDeviceId = selectedDeviceId,
    )
}

/** Every destination. On a wide window Home shows the empty detail side, since its list is already beside it. */
@Composable
private fun AppNavHost(
    navController: NavHostController,
    startDestination: Any,
    unitFormatter: UnitFormatter,
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<Onboarding> {
            HealthConnectSetupRoute(
                onContinue = {
                    onSetupComplete()
                    navController.navigate(Home) {
                        popUpTo<Onboarding> { inclusive = true }
                    }
                },
            )
        }
        composable<HealthConnectSetup> {
            HealthConnectSetupRoute(onContinue = null)
        }
        composable<Home> {
            if (LocalListDetail.current) {
                HomeDetailPlaceholder()
            } else {
                HomeList(navController = navController, opensBeside = false)
            }
        }
        composable<Settings> {
            SettingsRoute(
                onOpenHealthConnect = {
                    navController.navigate(HealthConnectSetup) { launchSingleTop = true }
                },
                onOpenBodyProfile = { navController.navigate(BodyProfile) { launchSingleTop = true } },
                onOpenConnectedApps = { navController.navigate(ConnectedApps) { launchSingleTop = true } },
                onOpenHealthData = { context.startActivity(Intent(context, PermissionsRationaleActivity::class.java)) },
            )
        }
        composable<ConnectedApps> {
            ConnectedAppsRoute()
        }
        composable<BodyProfile> {
            BodyProfileRoute(unitFormatter = unitFormatter)
        }
        deviceRoutes(navController)
    }
}
