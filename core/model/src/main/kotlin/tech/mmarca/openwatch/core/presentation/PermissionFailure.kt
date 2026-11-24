package tech.mmarca.openwatch.core.presentation

/**
 * The one rule for "this failed because a permission is missing": a
 * [SecurityException] anywhere in the cause chain. Here rather than beside `ScreenError`, in the
 * app, so the device modules apply the same rule.
 */
fun Throwable.isPermissionFailure(): Boolean =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .any { it is SecurityException }
