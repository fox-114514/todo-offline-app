package com.example.todooffline.data.remote

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.DELETE
import retrofit2.http.Path
import retrofit2.http.Query

interface TodoApi {
    @POST("api/auth/register")
    fun register(@Body request: RegisterRequest): Call<ApiResponse<AuthResponse>>

    @POST("api/auth/login")
    fun login(@Body request: AuthRequest): Call<ApiResponse<AuthResponse>>

    @POST("api/auth/logout")
    fun logout(@Header("Authorization") auth: String): Call<ApiResponse<Map<String, Boolean>>>

    @GET("api/me/circle")
    fun myCircle(@Header("Authorization") auth: String): Call<ApiResponse<CircleDto>>

    @POST("api/friends/requests")
    fun createFriendRequest(
        @Header("Authorization") auth: String,
        @Body request: FriendRequestCreateRequest,
    ): Call<ApiResponse<FriendRequestDto>>

    @GET("api/friends/requests/incoming")
    fun incomingFriendRequests(@Header("Authorization") auth: String): Call<ApiResponse<FriendRequestsResponse>>

    @GET("api/friends/requests/outgoing")
    fun outgoingFriendRequests(@Header("Authorization") auth: String): Call<ApiResponse<FriendRequestsResponse>>

    @POST("api/friends/requests/{requestId}/accept")
    fun acceptFriendRequest(
        @Header("Authorization") auth: String,
        @Path("requestId") requestId: String,
    ): Call<ApiResponse<FriendRequestDto>>

    @POST("api/friends/requests/{requestId}/reject")
    fun rejectFriendRequest(
        @Header("Authorization") auth: String,
        @Path("requestId") requestId: String,
    ): Call<ApiResponse<FriendRequestDto>>

    @GET("api/friends")
    fun friends(@Header("Authorization") auth: String): Call<ApiResponse<JoinedCirclesResponse>>

    @DELETE("api/friends/{circleId}")
    fun removeFriend(
        @Header("Authorization") auth: String,
        @Path("circleId") circleId: String,
    ): Call<ApiResponse<Map<String, Any>>>

    @GET("api/friends/{circleId}/ideas")
    fun friendIdeas(
        @Header("Authorization") auth: String,
        @Path("circleId") circleId: String,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
    ): Call<ApiResponse<FeedResponse>>

    @POST("api/circles/join")
    fun joinCircle(
        @Header("Authorization") auth: String,
        @Body request: JoinCircleRequest,
    ): Call<ApiResponse<CircleDto>>

    @GET("api/circles/joined")
    fun joinedCircles(@Header("Authorization") auth: String): Call<ApiResponse<JoinedCirclesResponse>>

    @DELETE("api/circles/{circleId}/leave")
    fun leaveCircle(
        @Header("Authorization") auth: String,
        @Path("circleId") circleId: String,
    ): Call<ApiResponse<Map<String, Any>>>

    @GET("api/feed")
    fun feed(
        @Header("Authorization") auth: String,
        @Query("circleId") circleId: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
    ): Call<ApiResponse<FeedResponse>>

    @GET("api/sync/pull")
    fun pull(
        @Header("Authorization") auth: String,
        @Query("cursor") cursor: String?,
    ): Call<ApiResponse<PullResponse>>

    @GET("api/ideas/{ideaId}")
    fun ideaDetail(
        @Header("Authorization") auth: String,
        @Path("ideaId") ideaId: String,
    ): Call<ApiResponse<FeedIdeaDto>>

    @PUT("api/reminders")
    fun updateReminder(
        @Header("Authorization") auth: String,
        @Body request: ReminderDto,
    ): Call<ApiResponse<ReminderDto>>

    @POST("api/ideas/{ideaId}/like")
    fun likeIdea(
        @Header("Authorization") auth: String,
        @Path("ideaId") ideaId: String,
    ): Call<ApiResponse<LikeResponse>>

    @DELETE("api/ideas/{ideaId}/like")
    fun unlikeIdea(
        @Header("Authorization") auth: String,
        @Path("ideaId") ideaId: String,
    ): Call<ApiResponse<LikeResponse>>

    @GET("api/ideas/{ideaId}/comments")
    fun comments(
        @Header("Authorization") auth: String,
        @Path("ideaId") ideaId: String,
    ): Call<ApiResponse<CommentsResponse>>

    @POST("api/ideas/{ideaId}/comments")
    fun createComment(
        @Header("Authorization") auth: String,
        @Path("ideaId") ideaId: String,
        @Body request: CommentRequest,
    ): Call<ApiResponse<CommentDto>>

    @POST("api/sync/push")
    fun push(
        @Header("Authorization") auth: String,
        @Body request: PushRequest,
    ): Call<ApiResponse<PushResponse>>
}
