package org.linphone.incomingcall.hiva

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.linphone.incomingcall.BuildConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object HivaGoldClient {

    const val BASE_URL = "https://hivaex.ir/"

    private var sessionStore: SessionStore? = null

    fun initSession(store: SessionStore) {
        sessionStore = store
    }

    fun accessTokenOrNull(): String? = sessionStore?.accessToken

    fun siteOrigin(): String = BASE_URL.trimEnd('/')

    fun mazanehWsBase(): String {
        val host = java.net.URI(BASE_URL).host ?: "hivaex.ir"
        return "wss://$host/mazaneh/ws/mazaneh/"
    }

    /** REST + WebSocket paths used by AutoTrade (mazaneh). */
    data class MazanehEndpoints(
        val apiBase: String,
        val wsPrice: String,
        val wsTrading: String,
        val wsLiveBars: String,
        val userInfo: String,
        val transactions: String,
        val ordersActive: String,
        val portfolioActive: String,
        val mazanehBars: String
    )

    fun mazanehEndpoints(): MazanehEndpoints {
        val ws = mazanehWsBase()
        val api = "${BASE_URL}mazaneh/api/"
        return MazanehEndpoints(
            apiBase = api,
            wsPrice = "${ws}price/",
            wsTrading = "${ws}trading/",
            wsLiveBars = "${ws}live-bars/",
            userInfo = "${api}user-info/",
            transactions = "${api}transaction/",
            ordersActive = "${api}order/active/",
            portfolioActive = "${api}portfolio/active/",
            mazanehBars = "${api}mazaneh-bars/"
        )
    }

    /**
     * hivaex.ir serves SPA HTML on bare `/captcha/image/...`; real image is under `/api/captcha/image/...`.
     */
    fun captchaImageUrl(imagePath: String): String = captchaImageUrlCandidates(imagePath).first()

    fun captchaImageUrlCandidates(imagePath: String): List<String> {
        val path = imagePath.trim()
        if (path.isBlank()) return listOf(siteOrigin())
        val normalized = if (path.startsWith("/")) path else "/$path"
        val origin = siteOrigin()
        val out = linkedSetOf<String>()
        when {
            normalized.startsWith("/captcha/") -> {
                out += "$origin/api$normalized"
                out += "$origin$normalized"
                out += "$origin/api/user/api$normalized"
                val key = normalized.removePrefix("/captcha/image/").trim('/')
                if (key.isNotBlank()) {
                    out += "$origin/api/user/api/captcha/image/$key/"
                }
            }
            normalized.startsWith("/api/") -> out += "$origin$normalized"
            else -> out += "$origin/api$normalized"
        }
        return out.toList()
    }

    private val loggingInterceptor: HttpLoggingInterceptor by lazy {
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(object : okhttp3.CookieJar {
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                    cookies.forEach { cookie ->
                        when (cookie.name) {
                            "access_token" -> sessionStore?.accessToken = cookie.value
                            "refresh_token" -> sessionStore?.refreshToken = cookie.value
                            // Legacy demo site cookies
                            "sessionid" -> sessionStore?.accessToken = cookie.value
                            "csrftoken" -> sessionStore?.csrfToken = cookie.value
                        }
                    }
                }

                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                    val cookies = mutableListOf<okhttp3.Cookie>()
                    sessionStore?.accessToken?.let { token ->
                        cookies += okhttp3.Cookie.Builder()
                            .name("access_token")
                            .value(token)
                            .domain(url.host)
                            .build()
                    }
                    sessionStore?.refreshToken?.let { token ->
                        cookies += okhttp3.Cookie.Builder()
                            .name("refresh_token")
                            .value(token)
                            .domain(url.host)
                            .build()
                    }
                    return cookies
                }
            })
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                
                // Use the exact User-Agent from the user's successful manual curl
                builder.header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36")
                builder.header("Accept", "*/*")
                builder.header("Accept-Language", "en-US,en;q=0.9,fa;q=0.8,zh-CN;q=0.7,zh;q=0.6")
                val origin = siteOrigin()
                builder.header("Origin", origin)
                builder.header("Referer", "$origin/mazaneh/")

                builder.header("sec-ch-ua", "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"")
                builder.header("sec-ch-ua-mobile", "?1")
                builder.header("sec-ch-ua-platform", "\"Android\"")
                builder.header("sec-fetch-dest", "empty")
                builder.header("sec-fetch-mode", "cors")
                builder.header("sec-fetch-site", "same-origin")

                val access = sessionStore?.accessToken
                val refresh = sessionStore?.refreshToken
                if (!access.isNullOrBlank()) {
                    builder.header("Authorization", "Bearer $access")
                    val cookieParts = mutableListOf("access_token=$access")
                    if (!refresh.isNullOrBlank()) cookieParts += "refresh_token=$refresh"
                    builder.header("Cookie", cookieParts.joinToString("; "))
                }
                chain.proceed(builder.build())
            }
            .addInterceptor(loggingInterceptor)
            .build()
    }

    val api: HivaGoldApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HivaGoldApi::class.java)
    }
}
