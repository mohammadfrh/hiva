package org.linphone.incomingcall.hiva

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.linphone.incomingcall.BuildConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object HivaGoldClient {

    const val BASE_URL = "https://demo.hivagold.org/"

    private var sessionStore: SessionStore? = null

    fun initSession(store: SessionStore) {
        sessionStore = store
    }

    fun accessTokenOrNull(): String? = sessionStore?.accessToken

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
                        if (cookie.name == "sessionid") {
                            sessionStore?.accessToken = cookie.value
                        } else if (cookie.name == "csrftoken") {
                            sessionStore?.csrfToken = cookie.value
                        }
                    }
                }

                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                    val cookies = mutableListOf<okhttp3.Cookie>()
                    sessionStore?.accessToken?.let {
                        cookies.add(
                            okhttp3.Cookie.Builder()
                                .name("sessionid")
                                .value(it)
                                .domain(url.host)
                                .build()
                        )
                    }
                    sessionStore?.csrfToken?.let {
                        cookies.add(
                            okhttp3.Cookie.Builder()
                                .name("csrftoken")
                                .value(it)
                                .domain(url.host)
                                .build()
                        )
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
                builder.header("Origin", "https://demo.hivagold.org")
                builder.header("Referer", "https://demo.hivagold.org/mazaneh/")
                
                // Add Sec headers to look more like a browser
                builder.header("sec-ch-ua", "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"")
                builder.header("sec-ch-ua-mobile", "?1")
                builder.header("sec-ch-ua-platform", "\"Android\"")
                builder.header("sec-fetch-dest", "empty")
                builder.header("sec-fetch-mode", "cors")
                builder.header("sec-fetch-site", "same-origin")

                val csrf = sessionStore?.csrfToken
                if (!csrf.isNullOrBlank()) {
                    builder.header("X-CSRFToken", csrf)
                }
                
                // Force include cookies for websockets and cases where cookieJar might be skipped
                val sessionid = sessionStore?.accessToken
                if (!sessionid.isNullOrBlank()) {
                    builder.header("Cookie", "sessionid=$sessionid; csrftoken=$csrf")
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
