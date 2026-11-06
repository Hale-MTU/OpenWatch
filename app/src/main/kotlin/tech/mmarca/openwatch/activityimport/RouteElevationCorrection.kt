package tech.mmarca.openwatch.activityimport

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where OpenVitals swaps a route's altitudes for offline DEM values, OpenWatch
 * keeps what the watch recorded: it ships no elevation tiles. The type stays so
 * the Garmin importer reads the same.
 */
@Singleton
class RouteElevationCorrector @Inject constructor() {
    fun correct(routeImport: RouteFileImport): RouteFileImport = routeImport
}
