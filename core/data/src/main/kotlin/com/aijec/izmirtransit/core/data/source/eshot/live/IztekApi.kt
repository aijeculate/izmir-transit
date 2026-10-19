package com.aijec.izmirtransit.core.data.source.eshot.live

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.create
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/** Raw bodies: decoding happens in [IztekLiveDataSource] so bad records can be skipped one by one. */
interface IztekApi {
    @GET("hatotobuskonumlari/{lineCode}")
    suspend fun lineBusPositions(@Path("lineCode") lineCode: String): Response<ResponseBody>

    @GET("duragayaklasanotobusler/{stopCode}")
    suspend fun approachingBuses(@Path("stopCode") stopCode: String): Response<ResponseBody>

    @GET("hattinyaklasanotobusleri/{lineCode}/{stopCode}")
    suspend fun lineApproachingBuses(
        @Path("lineCode") lineCode: String,
        @Path("stopCode") stopCode: String,
    ): Response<ResponseBody>

    companion object {
        const val BASE_URL = "https://openapi.izmir.bel.tr/api/iztek/"

        fun create(client: OkHttpClient, baseUrl: String = BASE_URL): IztekApi =
            Retrofit.Builder().baseUrl(baseUrl).client(client).build().create()

        /** 10 s timeouts as the brief requires. Retries are handled by the polling layer, not OkHttp. */
        fun httpClient(userAgent: String): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
            }
            .build()
    }
}
