package com.aijec.izmirtransit.di

import android.content.Context
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

    @Provides
    @Singleton
    fun iztekApi(): IztekApi = IztekApi.create(IztekApi.httpClient("IzmirTransit/0.1 (Android)"))

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
