package tech.mmarca.openwatch.ui.components

/**
 * An activity that can drop a touch that reached its window through another app's window above
 * it, so an overlay cannot steer a tap. A screen that gives another app trust turns it on while
 * it shows; the app's activity implements it.
 */
interface ObscuredTouchGuard {
    var dropObscuredTouches: Boolean
}
