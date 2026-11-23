package tech.mmarca.openwatch.features.watches

import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.ui.R as UiR
import tech.mmarca.openwatch.core.performance.DefaultDispatcherProvider
import tech.mmarca.openwatch.data.repository.WatchNotificationPrefsStore
import tech.mmarca.openwatch.testing.string
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme

/**
 * When the platform hands back no apps, the screen has to say so rather than look like it is
 * loading; when it cannot read them, the screen says that instead, never a misleading "no apps".
 */
class WatchNotificationAppsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeGateway(
        private val apps: List<InstalledApp>,
        private val throwsOnList: Boolean = false,
    ) : WatchNotificationsGateway {
        override fun isNotificationAccessGranted(): Boolean = true

        override fun openNotificationAccessSettings() = Unit

        override fun listLaunchableApps(): List<InstalledApp> {
            if (throwsOnList) error("no host")
            return apps
        }

        override fun pushConfiguration() = Unit
    }

    @Test
    fun aPhoneWithNoLaunchableAppsSaysSoRatherThanShowingAnEmptyList() {
        setScreen(FakeGateway(apps = emptyList()))

        awaitText(string(R.string.settings_watch_notifications_apps_empty))
        composeRule
            .onNodeWithText(string(R.string.settings_watch_notifications_apps_empty))
            .assertIsDisplayed()
    }

    @Test
    fun anAppListThatCannotBeReadSaysSoRatherThanStuckLoadingOrEmpty() {
        // A host that cannot answer must not spin forever, nor pass for a phone with no apps.
        setScreen(FakeGateway(apps = emptyList(), throwsOnList = true))

        awaitText(string(UiR.string.screen_error_generic))
        composeRule.onNodeWithText(string(UiR.string.screen_error_generic)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.settings_watch_notifications_apps_empty)).assertCountEquals(0)
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setScreen(gateway: WatchNotificationsGateway) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A throwaway preferences file, so the test never edits the real one.
        val prefs = context.getSharedPreferences(TEST_PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit { clear() }
        val store = WatchNotificationPrefsStore(prefs)
        // The app list only exists once forwarding is on and access is granted.
        store.enabled = true
        store.disclosureAccepted = true

        val viewModel = WatchNotificationAppsViewModel(
            store = store,
            gateway = gateway,
            dispatchers = DefaultDispatcherProvider,
        )
        composeRule.setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            // What the route does when the screen first appears.
            LaunchedEffect(Unit) { viewModel.loadApps() }
            OpenWatchTheme {
                WatchNotificationAppsScreen(
                    state = state,
                    onEnabledChange = viewModel::setEnabled,
                    onOpenAccessSettings = viewModel::openAccessSettings,
                    onOnlyAllowedChange = viewModel::setOnlyAllowed,
                    onHideSensitiveChange = viewModel::setHideSensitiveContent,
                    onSendsChange = viewModel::setSends,
                )
            }
        }
    }

    private companion object {
        const val TEST_PREFS_FILE = "openwatch_test_watch_notifications"
    }
}
