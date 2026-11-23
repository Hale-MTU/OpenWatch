package tech.mmarca.openwatch.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Singleton
import tech.mmarca.openwatch.BuildConfig
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.core.performance.DefaultDispatcherProvider
import tech.mmarca.openwatch.core.performance.DispatcherProvider
import tech.mmarca.openwatch.core.presentation.CurrentLocaleStrings
import tech.mmarca.openwatch.core.presentation.LocaleUnitSystemProvider
import tech.mmarca.openwatch.core.presentation.UnitFormatter
import tech.mmarca.openwatch.core.security.KeystoreSecretKey
import tech.mmarca.openwatch.data.local.device.DeviceDao
import tech.mmarca.openwatch.data.local.device.DeviceStorageExecutor
import tech.mmarca.openwatch.data.local.device.DeviceStateDao
import tech.mmarca.openwatch.data.local.OpenWatchDatabase
import tech.mmarca.openwatch.data.local.OpenWatchMigrations
import tech.mmarca.openwatch.data.local.garmin.GarminSleepMinuteDao
import tech.mmarca.openwatch.data.local.garmin.GarminWellnessDao
import tech.mmarca.openwatch.data.local.scale.ScaleWeighInDao
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.domain.preferences.SystemUnitSystemProvider
import tech.mmarca.openwatch.core.presentation.AppVersionName
import tech.mmarca.openwatch.wearlink.SecretSeal
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import tech.mmarca.openwatch.core.performance.ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** Seals the secrets the phone keeps: the scale's key and the watches' link tokens. */
    @Provides
    @Singleton
    fun provideSecretSeal(): SecretSeal = SecretSeal(KeystoreSecretKey())

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider

    /** The process's long-lived work. No handler: a bug in it still crashes, as it should. */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(dispatchers: DispatcherProvider): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.default)

    @Provides
    @Singleton
    fun provideOpenWatchDatabase(@ApplicationContext context: Context): OpenWatchDatabase =
        Room.databaseBuilder(context, OpenWatchDatabase::class.java, OpenWatchDatabase.NAME)
            .addMigrations(*OpenWatchMigrations.ALL)
            .build()

    @Provides
    @Singleton
    fun provideGarminWellnessDao(database: OpenWatchDatabase): GarminWellnessDao =
        database.garminWellnessDao()

    /** One thread for the registry and the per-device state; see [DeviceStorageExecutor]. */
    @Provides
    @Singleton
    @DeviceStorageExecutor
    fun provideDeviceStorageExecutor(): Executor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "device-storage") }

    @Provides
    @Singleton
    fun provideDeviceDao(database: OpenWatchDatabase): DeviceDao = database.deviceDao()

    @Provides
    @Singleton
    fun provideDeviceStateDao(database: OpenWatchDatabase): DeviceStateDao = database.deviceStateDao()

    @Provides
    @Singleton
    fun provideGarminSleepMinuteDao(database: OpenWatchDatabase): GarminSleepMinuteDao =
        database.garminSleepMinuteDao()

    @Provides
    @Singleton
    fun provideScaleWeighInDao(database: OpenWatchDatabase): ScaleWeighInDao =
        database.scaleWeighInDao()

    /** The version Settings shows: this build's, suffixes included. */
    @Provides
    @AppVersionName
    fun provideAppVersionName(): String = BuildConfig.VERSION_NAME

    @Provides
    @Singleton
    fun provideSystemUnitSystemProvider(): SystemUnitSystemProvider = LocaleUnitSystemProvider

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
