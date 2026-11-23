package tech.mmarca.openwatch.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app scaffold's snackbar, for a notice that needs no place on the screen, such as an
 * action that could not start. A result that belongs beside what it reports on stays inline.
 */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("LocalSnackbarHostState is provided by AppNavigation")
}
