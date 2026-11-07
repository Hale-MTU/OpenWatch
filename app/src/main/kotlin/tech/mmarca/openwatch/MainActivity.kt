package tech.mmarca.openwatch

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import tech.mmarca.openwatch.core.presentation.UnitFormatter
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.devices.core.pairing.CompanionDevicePairing
import tech.mmarca.openwatch.domain.model.HealthConnectAvailability
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway
import tech.mmarca.openwatch.navigation.AppNavigation
import tech.mmarca.openwatch.navigation.Screen
import tech.mmarca.openwatch.ui.components.AppBarState
import tech.mmarca.openwatch.ui.components.LocalAppBarState
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var preferences: OpenWatchPreferences
    @Inject lateinit var healthConnect: HealthConnectGateway
    @Inject lateinit var unitFormatter: UnitFormatter
    @Inject lateinit var companionDevicePairing: CompanionDevicePairing

    private var startDestination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectHealthConnectOnboardingIfNeeded(intent)) {
            return
        }
        enableEdgeToEdge()
        // The companion association dialog needs an Activity launcher. Without this
        // attach, no watch is ever associated.
        companionDevicePairing.attachToActivity(this)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { darkTheme },
                    ),
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { darkTheme },
                    ),
                )
            }

            OpenWatchTheme(dynamicColor = true) {
                val resolvedStartDestination = startDestination ?: run {
                    if (
                        preferences.onboardingDone &&
                        healthConnect.availability() == HealthConnectAvailability.AVAILABLE
                    ) {
                        Screen.Home.route
                    } else {
                        Screen.HealthConnectSetup.route
                    }
                }.also { destination ->
                    if (startDestination == null) {
                        startDestination = destination
                    }
                }
                val rememberedAppBarState = remember { AppBarState() }

                // Where each screen declares what it wants in the app bar.
                CompositionLocalProvider(LocalAppBarState provides rememberedAppBarState) {
                    AppNavigation(
                        unitFormatter = unitFormatter,
                        startDestination = resolvedStartDestination,
                        onSetupComplete = {
                            preferences.onboardingDone = true
                            startDestination = Screen.Home.route
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        companionDevicePairing.detachFromActivity(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        redirectHealthConnectOnboardingIfNeeded(intent)
    }

    private fun redirectHealthConnectOnboardingIfNeeded(intent: Intent?): Boolean {
        if (intent?.isHealthConnectOnboardingIntent() != true) return false
        startActivity(
            Intent(this, HealthConnectOnboardingActivity::class.java).apply {
                action = intent.action
                data = intent.data
                putExtras(intent)
            },
        )
        finish()
        return true
    }
}

private fun Intent.isHealthConnectOnboardingIntent(): Boolean {
    val action = action ?: return false
    return action == "androidx.health.ACTION_SHOW_ONBOARDING" ||
        action == "android.health.connect.action.SHOW_ONBOARDING"
}
