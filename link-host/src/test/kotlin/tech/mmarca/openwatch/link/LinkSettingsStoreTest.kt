package tech.mmarca.openwatch.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import tech.mmarca.openlink.OpenLinkSetting
import tech.mmarca.openlink.OpenLinkSettingKeys
import tech.mmarca.openlink.OpenLinkSettings
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.devices.core.DailyGoal

@OptIn(ExperimentalCoroutinesApi::class)
class LinkSettingsStoreTest {

    private val prefs = FakeSharedPreferences()
    private var now = 1_000L
    private fun store() = LinkSettingsStore(prefs) { now }

    @Test
    fun `a change made here is stamped now and kept across a restart`() {
        store().setLocal(OpenLinkSettingKeys.GOAL_STEPS, listOf(9_000.0))
        val again = store().linkSettings()
        assertEquals(9_000.0, again.number(OpenLinkSettingKeys.GOAL_STEPS)!!, 0.0)
        assertEquals(1_000L, again.entries["goal.steps"]!!.updatedAtMillis)
    }

    @Test
    fun `a pushed setting wins only when it is newer, key by key`() {
        val store = store()
        store.setLocal(OpenLinkSettingKeys.GOAL_STEPS, listOf(9_000.0))
        val incoming = OpenLinkSettings()
            .with(OpenLinkSettingKeys.GOAL_STEPS, listOf(5_000.0), atMillis = 500L)
            .with(OpenLinkSettingKeys.GOAL_WATER_ML, listOf(2_500.0), atMillis = 500L)
        assertTrue(store.accept(incoming))
        assertEquals(9_000.0, store.linkSettings().number(OpenLinkSettingKeys.GOAL_STEPS)!!, 0.0)
        assertEquals(2_500.0, store.linkSettings().number(OpenLinkSettingKeys.GOAL_WATER_ML)!!, 0.0)
        assertFalse(store.accept(incoming))
    }

    @Test
    fun `a pushed setting is answered with a publish even when it changes nothing`() = runTest {
        val store = store()
        val published = mutableListOf<OpenLinkSettings>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.changes.collect { published += it } }
        store.setLocal(OpenLinkSettingKeys.GOAL_STEPS, listOf(9_000.0))

        assertFalse(store.accept(OpenLinkSettings().with(OpenLinkSettingKeys.GOAL_STEPS, listOf(5_000.0), atMillis = 500L)))

        assertEquals(listOf(store.linkSettings(), store.linkSettings()), published)
    }

    @Test
    fun `an answer given before the link collects still reaches it`() = runTest {
        val store = store()
        store.accept(OpenLinkSettings().with(OpenLinkSettingKeys.GOAL_WATER_ML, listOf(2_500.0), atMillis = 500L))
        val published = mutableListOf<OpenLinkSettings>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.changes.collect { published += it } }

        assertEquals(listOf(store.linkSettings()), published)
    }

    @Test
    fun `a time this clock set ahead before it was put right does not pin a goal`() {
        val year = 365L * 24 * 60 * 60 * 1000
        now = 1_000L + year
        store().setLocal(OpenLinkSettingKeys.GOAL_STEPS, listOf(9_000.0))
        now = 1_000L
        val store = store()

        assertTrue(store.accept(OpenLinkSettings(mapOf("goal.steps" to OpenLinkSetting(5_000.0, 1_001L)))))

        assertEquals(5_000.0, store.linkSettings().number(OpenLinkSettingKeys.GOAL_STEPS)!!, 0.0)
    }

    @Test
    fun `a cleared goal crosses and is kept as cleared`() {
        val store = store()
        store.setLocal(OpenLinkSettingKeys.GOAL_STEPS, listOf(9_000.0))
        now = 2_000L
        assertTrue(store.accept(OpenLinkSettings().with(OpenLinkSettingKeys.GOAL_STEPS, emptyList(), atMillis = 1_500L)))
        assertNull(store().linkSettings().number(OpenLinkSettingKeys.GOAL_STEPS))
        assertTrue(store().linkSettings().entries.containsKey("goal.steps"))
    }

    @Test
    fun `a later change here is later than the one it replaces even within a millisecond`() {
        val store = store()
        store.setLocal(OpenLinkSettingKeys.GOAL_STEPS, listOf(9_000.0))
        store.setLocal(OpenLinkSettingKeys.GOAL_STEPS, listOf(10_000.0))
        assertEquals(1_001L, store.linkSettings().entries["goal.steps"]!!.updatedAtMillis)
    }

    @Test
    fun `a value its key does not take is not kept`() {
        val store = store()
        store.setLocal(OpenLinkSettingKeys.GOAL_STEPS, listOf(50.0))
        assertTrue(store.linkSettings().isEmpty)
    }

    @Test
    fun `the daily goals are what a watch reads and sets, in plain numbers`() {
        val store = store()
        assertTrue("a goal never set has no entry", store.current().isEmpty())
        store.set(DailyGoal.STEPS, 9_000)
        store.set(DailyGoal.ACTIVE_KCAL, null)
        assertEquals(mapOf(DailyGoal.STEPS to 9_000, DailyGoal.ACTIVE_KCAL to null), store.current())
        assertEquals(9_000.0, store.linkSettings().number(OpenLinkSettingKeys.GOAL_STEPS)!!, 0.0)
    }
}
