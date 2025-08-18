package com.spymag.portfoliowidget.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClient {
    val instance: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
