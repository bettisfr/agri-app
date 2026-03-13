package it.unipg.agriapp.data

import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface AgriApi {
    @GET("/api/v1/health")
    suspend fun health(): HealthResponse

    @GET("/api/v1/system/status")
    suspend fun systemStatus(): SystemStatusResponse

    @GET("/api/v1/images")
    suspend fun images(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): ImagesResponse

    @POST("/api/v1/capture/oneshot")
    suspend fun oneShot(): CaptureResponse

    @GET("/api/v1/network/mode")
    suspend fun networkMode(): NetworkModeResponse

    @POST("/api/v1/network/mode")
    suspend fun setNetworkMode(@Body req: NetworkModeRequest): NetworkModeResponse
}
