package tech.mmarca.openwatch.link

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** An app's name as the launcher shows it, for a state holder that holds no `Context`. */
fun interface AppLabelResolver {
    /** The name, or [packageName] when the app is not visible to this one. */
    fun label(packageName: String): String
}

/** [AppLabelResolver] from the package manager. */
class SystemAppLabelResolver(private val context: Context) : AppLabelResolver {
    override fun label(packageName: String): String = appLabel(context, packageName)
}

/** The app's name as the launcher shows it, or its package name if it is not visible. */
internal fun appLabel(context: Context, packageName: String): String = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0)
    }
    pm.getApplicationLabel(info).toString()
}.getOrDefault(packageName)
