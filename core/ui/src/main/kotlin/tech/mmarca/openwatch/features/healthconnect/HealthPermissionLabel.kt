package tech.mmarca.openwatch.features.healthconnect

import android.content.Context

/**
 * The permission's name as Android shows it ("Read heart rate"), in the phone's language;
 * the constant made readable if the platform has no label for it.
 */
fun healthPermissionLabel(context: Context, permission: String): String {
    val pm = context.packageManager
    val label = runCatching { pm.getPermissionInfo(permission, 0).loadLabel(pm).toString() }.getOrNull()
        ?.takeIf { it.isNotBlank() && it != permission }
    return (label ?: readablePermission(permission)).replaceFirstChar { it.titlecase() }
}

/** "android.permission.health.WRITE_HEART_RATE" → "Write heart rate". */
fun readablePermission(permission: String): String =
    permission.substringAfterLast('.').lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
