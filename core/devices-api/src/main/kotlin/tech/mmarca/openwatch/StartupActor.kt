package tech.mmarca.openwatch

/**
 * One thing the app starts at every process start, named for the log. Each module contributes
 * its own `@IntoSet` (a device family's from its own Hilt module); the app's `AppStartupActors`
 * starts them. Here, not in the app, so a device module can contribute one.
 */
class StartupActor(val name: String, val start: () -> Unit)
