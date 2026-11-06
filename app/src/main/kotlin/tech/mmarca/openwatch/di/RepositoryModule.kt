package tech.mmarca.openwatch.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import tech.mmarca.openwatch.data.repository.ActivityWriteRepository
import tech.mmarca.openwatch.data.repository.CoMapsNavigationRepositoryImpl
import tech.mmarca.openwatch.data.repository.GarminSleepMinuteRepositoryImpl
import tech.mmarca.openwatch.data.repository.GarminWellnessRepositoryImpl
import tech.mmarca.openwatch.data.repository.OpenWatchPreferences
import tech.mmarca.openwatch.data.repository.ScaleWeighInRepositoryImpl
import tech.mmarca.openwatch.data.repository.contract.ActivityRepository
import tech.mmarca.openwatch.data.repository.contract.BodyProfilePreferences
import tech.mmarca.openwatch.data.repository.contract.CoMapsNavigationRepository
import tech.mmarca.openwatch.data.repository.contract.GarminSleepMinuteRepository
import tech.mmarca.openwatch.data.repository.contract.GarminWellnessRepository
import tech.mmarca.openwatch.data.repository.contract.ScaleWeighInRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindActivityRepository(impl: ActivityWriteRepository): ActivityRepository

    @Binds
    @Singleton
    abstract fun bindCoMapsNavigationRepository(impl: CoMapsNavigationRepositoryImpl): CoMapsNavigationRepository

    @Binds
    @Singleton
    abstract fun bindGarminWellnessRepository(impl: GarminWellnessRepositoryImpl): GarminWellnessRepository

    @Binds
    @Singleton
    abstract fun bindGarminSleepMinuteRepository(impl: GarminSleepMinuteRepositoryImpl): GarminSleepMinuteRepository

    @Binds
    @Singleton
    abstract fun bindScaleWeighInRepository(impl: ScaleWeighInRepositoryImpl): ScaleWeighInRepository

    @Binds
    @Singleton
    abstract fun bindBodyProfilePreferences(impl: OpenWatchPreferences): BodyProfilePreferences
}
