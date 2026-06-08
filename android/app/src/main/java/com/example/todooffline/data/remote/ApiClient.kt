package com.example.todooffline.data.remote

import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8000/"

    fun create(baseUrl: String = DEFAULT_BASE_URL): TodoApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TodoApi::class.java)
    }
}

fun <T> Call<ApiResponse<T>>.executeData(): T {
    val response = execute()
    if (!response.isSuccessful) {
        val body = response.errorBody()?.string().orEmpty()
        throw IllegalStateException(body.ifBlank { "HTTP ${response.code()}" })
    }
    val apiResponse = response.body() ?: throw IllegalStateException("Empty response")
    if (apiResponse.code !in 200..299) {
        throw IllegalStateException(apiResponse.message)
    }
    return apiResponse.data ?: throw IllegalStateException("Missing response data")
}
