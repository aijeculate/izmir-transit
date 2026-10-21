package com.aijec.izmirtransit.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.aijec.izmirtransit.BuildConfig
import com.aijec.izmirtransit.core.data.places.GoogleApiConfig
import java.security.MessageDigest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.aijec.izmirtransit.core.data.importer.EshotDownloader
import com.aijec.izmirtransit.core.data.importer.OtherModesDownloader
import com.aijec.izmirtransit.core.data.importer.StaticDataRefresher
import com.aijec.izmirtransit.core.data.live.EtaEstimator
import com.aijec.izmirtransit.core.data.live.GhostFilter
import com.aijec.izmirtransit.core.data.live.RequestPacer
import com.aijec.izmirtransit.core.data.repository.LineRepository
import com.aijec.izmirtransit.core.data.repository.StopRepository
import com.aijec.izmirtransit.core.data.repository.TimetableRepository
import com.aijec.izmirtransit.core.data.source.eshot.live.IztekApi
import com.aijec.izmirtransit.core.database.AndroidDatabases
import com.aijec.izmirtransit.core.database.IzmirDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import com.aijec.izmirtransit.core.data.update.AppVersion
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): IzmirDatabase = AndroidDatabases.prepackaged(context)

    /** One pacer for the whole app: the İztek server rate-limits per client. */
    @Provides
    @Singleton
    fun requestPacer(): RequestPacer = RequestPacer()

    /** One HTTP client for the live API and the dataset downloads. */
    @Provides
    fun appVersion(): AppVersion = AppVersion(BuildConfig.VERSION_NAME)

    @Provides
    @Singleton
    fun httpClient(): OkHttpClient = IztekApi.httpClient("IzmirTransit/0.1 (Android)")

    @Provides
    @Singleton
    fun iztekApi(client: OkHttpClient): IztekApi = IztekApi.create(client)

    @Provides
    @Singleton
    fun staticDataRefresher(
        client: OkHttpClient,
        database: IzmirDatabase,
        @ApplicationContext context: Context,
        clock: Clock,
        pacer: RequestPacer,
    ): StaticDataRefresher = StaticDataRefresher(
        downloader = EshotDownloader(client),
        database = database,
        workDir = context.cacheDir,
        clock = clock,
        otherModes = OtherModesDownloader(client, pacer),
    )

    /** The Places key (local.properties PLACES_API_KEY) plus the app identity it is restricted to. */
    @Provides
    @Singleton
    fun googleApiConfig(@ApplicationContext context: Context): GoogleApiConfig =
        GoogleApiConfig(BuildConfig.PLACES_API_KEY, context.packageName, signingCertificateSha1(context))

    /** SHA-1 of the signing certificate as Google wants it: upper-case hex, no colons. */
    private fun signingCertificateSha1(context: Context): String = runCatching {
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }?.toByteArray() ?: return ""
        MessageDigest.getInstance("SHA-1").digest(certificate).joinToString("") { "%02X".format(it) }
    }.getOrDefault("")

    /** Favourites and settings. One instance per file is required by DataStore. */
    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("user_preferences") }

    @Provides
    fun ghostFilter(): GhostFilter = GhostFilter()

    @Provides
    fun etaEstimator(): EtaEstimator = EtaEstimator()

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    // Singletons so LineRepository's shape cache is shared between screens.
    @Provides
    @Singleton
    fun lineRepository(database: IzmirDatabase): LineRepository = LineRepository(database)

    @Provides
    @Singleton
    fun stopRepository(database: IzmirDatabase): StopRepository = StopRepository(database)

    @Provides
    @Singleton
    fun timetableRepository(database: IzmirDatabase): TimetableRepository = TimetableRepository(database)
}
