package tech.mmarca.openwatch.link

import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openlink.host.OpenLinkHostService

/** The exported OpenLink service. The trust store, not a permission, decides who gets what. */
@AndroidEntryPoint
class OpenWatchLinkService : OpenLinkHostService() {
    @Inject
    lateinit var linkHost: OpenLinkHost

    override val host: OpenLinkHost get() = linkHost
}
