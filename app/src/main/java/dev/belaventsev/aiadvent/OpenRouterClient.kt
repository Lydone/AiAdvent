package dev.belaventsev.aiadvent

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object OpenRouterClient {

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okhttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    private val servicesByBaseUrl = mutableMapOf<String, OpenRouterService>()

    fun serviceFor(baseUrl: String): OpenRouterService =
        servicesByBaseUrl.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okhttp)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OpenRouterService::class.java)
        }
}
