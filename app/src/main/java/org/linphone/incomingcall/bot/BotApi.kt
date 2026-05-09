package org.linphone.incomingcall.bot

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface BotApi {
    @GET("api/health")
    suspend fun health(): JsonObject

    @GET("api/signal")
    suspend fun signal(): JsonObject

    @POST("api/market/signal")
    suspend fun marketSignal(): JsonObject

    @GET("api/history/today-summary")
    suspend fun todaySummary(): JsonObject

    @GET("api/history/today-signals")
    suspend fun todaySignals(): JsonArray

    @GET("api/mock/list")
    suspend fun mockList(): JsonArray

    @GET("api/backtest/list")
    suspend fun backtestList(): JsonArray

    @POST("api/backtest/run")
    suspend fun backtestRun(@Body body: JsonObject): JsonObject

    @GET("api/backtest/result/{dataset}/{profile}")
    suspend fun backtestResult(
        @Path("dataset") dataset: String,
        @Path("profile") profile: String
    ): JsonObject

    @GET("api/market/indicators")
    suspend fun marketIndicators(@Query("tf") tf: String): JsonObject

    @GET("api/market/snapshot")
    suspend fun marketSnapshot(): JsonObject

    @POST("api/market/backtest")
    suspend fun marketBacktest(): JsonObject

    @POST("api/market/place-order")
    suspend fun placeOrder(@Body body: JsonObject): JsonObject

    @GET("api/position/status")
    suspend fun positionStatus(): JsonObject

    @POST("api/position/reset")
    suspend fun positionReset(): JsonObject

    @POST("api/position/adopt")
    suspend fun positionAdopt(@Body body: JsonObject): JsonObject

    @GET("api/auth/status")
    suspend fun authStatus(): JsonObject

    @POST("api/auth/unlock")
    suspend fun authUnlock(): JsonObject

    @GET("api/config")
    suspend fun configGet(): JsonObject

    @POST("api/config/update")
    suspend fun configUpdate(@Body body: JsonObject): JsonObject

    @POST("api/config/refresh-token")
    suspend fun refreshToken(): JsonObject

    @GET("api/auth/captcha")
    suspend fun authCaptcha(): JsonObject

    @POST("api/config/login")
    suspend fun configLogin(@Body body: JsonObject): JsonObject

    @POST("api/mock/download")
    suspend fun mockDownload(@Body body: JsonObject): JsonObject

    @POST("api/mock/download-month")
    suspend fun mockDownloadMonth(@Body body: JsonObject): JsonObject

    @GET("api/market/cache/list")
    suspend fun cacheList(): JsonArray

    @DELETE("api/market/cache/{tf}/{date}")
    suspend fun cacheDelete(
        @Path("tf") tf: String,
        @Path("date") date: String
    ): JsonObject

    @POST("api/market/cache/download")
    suspend fun cacheDownload(@Body body: JsonObject): JsonObject

    @POST("api/market/cache/download-month")
    suspend fun cacheDownloadMonth(@Body body: JsonObject): JsonObject
}
