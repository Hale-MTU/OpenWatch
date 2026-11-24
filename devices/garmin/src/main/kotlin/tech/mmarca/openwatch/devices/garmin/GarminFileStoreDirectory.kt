package tech.mmarca.openwatch.devices.garmin

import android.content.Context
import java.io.File

/** Under the app's files dir, matching the Flutter build's `garmin/`. */
private const val FILE_STORE_DIRECTORY = "garmin"

/** The app's one store of downloaded watch files. The store itself is the protocol module's. */
fun garminFileStore(context: Context): GarminFileStore =
    GarminFileStore(resolveDirectory = { File(context.filesDir, FILE_STORE_DIRECTORY) })
