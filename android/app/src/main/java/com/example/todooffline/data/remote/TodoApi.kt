package com.example.todooffline.data.remote

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

interface TodoApi {
    @POST("api/auth/register")
    fun register(@Body request: RegisterRequest): Call<ApiResponse<AuthResponse>>

    @POST("api/auth/login")
    fun login(@Body request: AuthRequest): Call<ApiResponse<AuthResponse>>

    @POST("api/auth/logout")
    fun logout(@Header("Authorization") auth: String): Call<ApiResponse<Map<String, Boolean>>>

    @GET("api/sync/pull")
    fun pull(
        @Header("Authorization") auth: String,
        @Query("cursor") cursor: String?,
    ): Call<ApiResponse<PullResponse>>

    @PUT("api/reminders")
    fun updateReminder(
        @Header("Authorization") auth: String,
        @Body request: ReminderDto,
    ): Call<ApiResponse<ReminderDto>>

    @POST("api/sync/push")
    fun push(
        @Header("Authorization") auth: String,
        @Body request: PushRequest,
    ): Call<ApiResponse<PushResponse>>
}
