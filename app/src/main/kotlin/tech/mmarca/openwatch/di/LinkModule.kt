package tech.mmarca.openwatch.di

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import tech.mmarca.openlink.host.OpenLinkCallerResolver
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openlink.host.OpenLinkHostCore
import tech.mmarca.openlink.host.OpenLinkTrustStore
import tech.mmarca.openlink.host.SharedPreferencesTrustStorage
import tech.mmarca.openwatch.MainActivity
import tech.mmarca.openwatch.StartupActor
import tech.mmarca.openwatch.devices.core.SharedGoals
import tech.mmarca.openwatch.domain.preferences.SharedUnits
import tech.mmarca.openwatch.link.AppLabelResolver
import tech.mmarca.openwatch.link.ConnectedAppsIntent
import tech.mmarca.openwatch.link.FirstPartyApps
import tech.mmarca.openwatch.link.InstalledApps
import tech.mmarca.openwatch.link.LinkSettingsStore
import tech.mmarca.openwatch.link.LinkSharedUnits
import tech.mmarca.openwatch.link.SystemInstalledApps
import tech.mmarca.openwatch.link.OpenWatchLinkCapabilities
import tech.mmarca.openwatch.link.OpenWatchLinkPublisher
import tech.mmarca.openwatch.link.SystemAppLabelResolver

/** The OpenLink host: the trust rules, the binder, and what OpenWatch offers through it. */
@Module
@InstallIn(SingletonComponent::class)
object LinkModule {
    /** OpenLink clients hear about device and profile changes while they are bound. */
    @Provides
    @IntoSet
    fun provideLinkPublisherAtStart(publisher: OpenWatchLinkPublisher) = StartupActor("openlink publisher", publisher::start)


    /** The daily goals the connected apps share, as a watch's sync and card read and set them. */
    @Provides
    fun provideSharedGoals(store: LinkSettingsStore): SharedGoals = store

    /** The units a connected app (OpenVitals) decides, which OpenWatch's own give way to. */
    @Provides
    fun provideSharedUnits(units: LinkSharedUnits): SharedUnits = units

    @Provides
    @Singleton
    fun provideInstalledApps(@ApplicationContext context: Context): InstalledApps = SystemInstalledApps(context)

    @Provides
    @Singleton
    fun provideFirstPartyApps(@ApplicationContext context: Context): FirstPartyApps = FirstPartyApps.from(context)

    /**
     * The Connected apps screen, which a consent request and the streaming notification open:
     * the app's own activity with the consent action, one pending intent per request code.
     */
    @Provides
    fun provideConnectedAppsIntent(@ApplicationContext context: Context): ConnectedAppsIntent =
        ConnectedAppsIntent { requestCode ->
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPENLINK_CONSENT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    @Provides
    fun provideAppLabelResolver(@ApplicationContext context: Context): AppLabelResolver = SystemAppLabelResolver(context)

    @Provides
    @Singleton
    fun provideOpenLinkHostCore(@ApplicationContext context: Context, firstParty: FirstPartyApps): OpenLinkHostCore = OpenLinkHostCore(
        store = OpenLinkTrustStore(SharedPreferencesTrustStorage.create(context)),
        knownClients = firstParty.knownClients,
    )

    @Provides
    @Singleton
    fun provideOpenLinkHost(
        @ApplicationContext context: Context,
        core: OpenLinkHostCore,
        capabilities: OpenWatchLinkCapabilities,
        connectedApps: ConnectedAppsIntent,
    ): OpenLinkHost = OpenLinkHost(
        core = core,
        resolver = OpenLinkCallerResolver(context),
        capabilities = capabilities,
        consentIntent = { packageName -> connectedApps.pendingIntent(packageName.hashCode()) },
    )
}
