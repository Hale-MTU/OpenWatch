package tech.mmarca.openwatch.navigation

import android.app.Application
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.mmarca.openwatch.R

/** The typed destinations: Navigation takes every argument type they use, and each has its title. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoutesTest {

    /** Home and the watch destinations as the app declares them. Building it maps every argument to a NavType. */
    private fun graph(): NavGraph {
        val navController = NavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }
        return navController.createGraph(startDestination = Home) {
            composable<Home> {}
            composable<Onboarding> {}
            watchRoutes(navController)
        }
    }

    @Test
    fun `the watch destinations build, and each has its title or titles itself`() {
        val graph = graph()

        assertNull(graph.findNode<WatchDevice>()!!.titleRes())
        assertEquals(R.string.settings_watch_data_title, graph.findNode<WatchData>()!!.titleRes())
        assertEquals(R.string.settings_watch_on_device_settings, graph.findNode<WatchSettings>()!!.titleRes())
        assertEquals(R.string.settings_watch_point_title, graph.findNode<WatchSendPoint>()!!.titleRes())
    }

    @Test
    fun `only Home and the first run are roots`() {
        val graph = graph()

        assertTrue(graph.findNode<Home>()!!.isRoot)
        assertTrue(graph.findNode<Onboarding>()!!.isRoot)
        assertFalse(graph.findNode<WatchDevice>()!!.isRoot)
    }

    @Test
    fun `a destination's arguments come back as they were given, absent ones absent`() {
        val point = WatchSendPoint(deviceId = "w1", latitude = -33.8568, longitude = 151.2153, name = "Opera House", unreadable = true)

        assertEquals(point, routeHandle(point).toRoute<WatchSendPoint>())
        assertEquals(WatchSendPoint(), routeHandle(WatchSendPoint()).toRoute<WatchSendPoint>())
        assertEquals(WatchSettings("w1", 7), routeHandle(WatchSettings("w1", 7)).toRoute<WatchSettings>())
    }
}
