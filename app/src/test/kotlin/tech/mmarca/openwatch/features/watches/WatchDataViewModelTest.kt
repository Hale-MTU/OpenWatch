package tech.mmarca.openwatch.features.watches

import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.data.repository.testDeviceRepository
import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openwatch.data.repository.contract.GarminWellnessRepository
import tech.mmarca.openwatch.domain.model.GarminWellnessMetric
import tech.mmarca.openwatch.domain.model.GarminWellnessSample
import tech.mmarca.openwatch.util.MainDispatcherRule

/** The watch-data screen. What [loadWatchMetrics] computes is covered by WatchMetricsTest. */
class WatchDataViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<GarminWellnessRepository>()

    private fun stubEmpty() {
        coEvery { repository.latest(any(), any()) } returns null
        coEvery { repository.samplesBetween(any(), any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `it shows loading until the first read lands`() = runTest {
        val read = CompletableDeferred<GarminWellnessSample?>()
        stubEmpty()
        coEvery { repository.latest(GarminWellnessMetric.entries.first(), any()) } coAnswers { read.await() }

        val vm = WatchDataViewModel(SavedStateHandle(), repository, testDeviceRepository())
        assertTrue(vm.uiState.value.isLoading)

        read.complete(null)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `it shows the latest reading of a stored metric`() = runTest {
        stubEmpty()
        val sample = GarminWellnessSample(
            metric = GarminWellnessMetric.SLEEP_SCORE,
            time = Instant.parse("2026-06-10T06:00:00Z"),
            value = 81,
        )
        coEvery { repository.latest(GarminWellnessMetric.SLEEP_SCORE, any()) } returns sample

        val vm = WatchDataViewModel(SavedStateHandle(), repository, testDeviceRepository())

        assertEquals(81L, vm.uiState.value.metrics.valueOf(GarminWellnessMetric.SLEEP_SCORE))
        assertFalse(vm.uiState.value.metrics.isEmpty)
    }

    @Test
    fun `a failed read shows a failure, not the empty state of a watch that sent nothing`() = runTest {
        coEvery { repository.latest(any(), any()) } throws IllegalStateException("database is locked")

        val vm = WatchDataViewModel(SavedStateHandle(), repository, testDeviceRepository())

        assertFalse(vm.uiState.value.isLoading)
        // The translated fallback, never the exception's own English text.
        assertEquals(ScreenError.Text(R.string.screen_error_generic), vm.uiState.value.error)
    }

    @Test
    fun `refresh reads again`() = runTest {
        stubEmpty()
        val vm = WatchDataViewModel(SavedStateHandle(), repository, testDeviceRepository())

        vm.refresh()

        coVerify(exactly = 2) { repository.latest(GarminWellnessMetric.SLEEP_SCORE, any()) }
    }
}
