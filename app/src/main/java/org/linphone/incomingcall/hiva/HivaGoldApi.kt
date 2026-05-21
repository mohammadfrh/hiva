package org.linphone.incomingcall.hiva

import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface HivaGoldApi {

    @GET("api/user/api/captcha-image/")
    suspend fun getCaptchaImage(): CaptchaImageResponse

    @POST("api/user/api/captcha-verify/")
    suspend fun verifyCaptcha(@Body body: CaptchaVerifyRequest): JsonObject

    @POST("api/user/api/auth/login/")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("api/user/api/user-info/")
    suspend fun getUserInfo(): UserInfoResponse

    @GET("api/user/api/me/")
    suspend fun getMe(): JsonObject

    @GET("api/profile/api/tether-rate/")
    suspend fun getTetherRate(): TetherRateResponse

    @GET("api/profile/v2/api/get-balance/")
    suspend fun getBalance(): BalanceResponse

    @GET("mazaneh/api/transaction/")
    suspend fun getMazanehTransactions(
        @Query("page") page: Int = 1
    ): JsonObject // Hiva returns a paged object for transactions



    // Mazaneh / Market Endpoints
    @GET("mazaneh/api/status/")
    suspend fun getMazanehStatus(): MazanehStatus

    @GET("mazaneh/api/order/active/")
    suspend fun getMazanehActiveOrders(): List<JsonObject>

    @GET("mazaneh/api/static-value/")
    suspend fun getMazanehStaticValues(): MazanehStaticValues

    @GET("mazaneh/api/portfolio/active/")
    suspend fun getMazanehActivePortfolio(): MazanehPortfolio

    @POST("mazaneh/api/portfolio/create/")
    suspend fun createMazanehPortfolio(@Body body: JsonObject): MazanehPortfolio

    @GET("mazaneh/api/mazaneh-bars/")
    suspend fun getMazanehBars(
        @Query("symbol") symbol: String = "mazaneh",
        @Query("from") from: Long,
        @Query("to") to: Long,
        @Query("resolution") resolution: String = "1"
    ): List<MazanehBar>

    @POST("mazaneh/api/order/create/")
    suspend fun createMazanehOrder(@Body body: JsonObject): JsonObject

    @POST("mazaneh/api/transaction/edit/{id}/")
    suspend fun editMazanehTransaction(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @POST("mazaneh/api/transaction/close/{id}/")
    suspend fun closeMazanehTransaction(@Path("id") id: Long, @Body body: JsonObject = JsonObject()): JsonObject

    @GET("mazaneh/api/user-info/")
    suspend fun getMazanehUserInfo(): JsonObject

    @POST("mazaneh/api/order/cancel/{id}/")
    suspend fun cancelMazanehOrder(@Path("id") id: Long, @Body body: JsonObject = JsonObject()): JsonObject

    @POST("mazaneh/api/portfolio/close/{id}/")
    suspend fun closeMazanehPortfolio(@Path("id") id: Long, @Body body: JsonObject = JsonObject()): JsonObject
}
