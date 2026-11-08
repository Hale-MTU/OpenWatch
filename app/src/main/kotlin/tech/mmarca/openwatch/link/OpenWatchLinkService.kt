package tech.mmarca.openwatch.link

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openlink.host.OpenLinkHostService

/** The exported OpenLink service; the manifest guards it with this app's OPENLINK permission. */
@AndroidEntryPoint
class OpenWatchLinkService : OpenLinkHostService() {
    @Inject
    lateinit var linkHost: OpenLinkHost

    override val host: OpenLinkHost get() = linkHost
}
