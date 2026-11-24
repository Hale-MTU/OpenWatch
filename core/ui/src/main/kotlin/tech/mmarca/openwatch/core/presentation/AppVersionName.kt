package tech.mmarca.openwatch.core.presentation

import javax.inject.Qualifier

/** The app's version name, which only the app module's build knows: `di/AppModule` provides it, Settings shows it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppVersionName
