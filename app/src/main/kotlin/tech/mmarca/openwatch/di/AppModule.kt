package tech.mmarca.openwatch.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.performance.DefaultDispatcherProvider
import tech.mmarca.openwatch.core.performance.DispatcherProvider
import tech.mmarca.openwatch.core.presentation.CurrentLocaleStrings
import tech.mmarca.openwatch.core.presentation.UnitFormatter
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.domain.preferences.SystemUnitSystemProvider

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider

    @Provides
    @Singleton
    fun provideSystemUnitSystemProvider(): SystemUnitSystemProvider =
        SystemUnitSystemProvider.Default

    @Provides
    @Singleton
    fun provideUnitFormatter(
        @ApplicationContext context: Context,
        preferences: OpenWatchPreferences,
    ): UnitFormatter {
        val strings = CurrentLocaleStrings(context)
        return UnitFormatter(
            unitSystemProvider = { preferences.unitSystem },
            unitOverrideProvider = { null },
            heartRateUnitProvider = { strings.get(R.string.unit_bpm) },
        )
    }
}
