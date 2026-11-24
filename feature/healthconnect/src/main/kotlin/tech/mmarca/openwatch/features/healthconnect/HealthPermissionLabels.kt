package tech.mmarca.openwatch.features.healthconnect

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** A Health Connect permission's name as Android shows it, for a state holder that holds no `Context`. */
fun interface HealthPermissionLabels {
    fun label(permission: String): String
}

/** [HealthPermissionLabels] from the package manager. */
class SystemHealthPermissionLabels(private val context: Context) : HealthPermissionLabels {
    override fun label(permission: String): String = healthPermissionLabel(context, permission)
}

@Module
@InstallIn(SingletonComponent::class)
object HealthPermissionLabelsModule {
    @Provides
    fun provideHealthPermissionLabels(@ApplicationContext context: Context): HealthPermissionLabels =
        SystemHealthPermissionLabels(context)
}
