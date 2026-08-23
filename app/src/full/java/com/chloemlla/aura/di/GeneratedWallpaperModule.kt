package com.chloemlla.aura.di

import com.chloemlla.aura.data.remote.stability.StabilityAiApi
import com.chloemlla.aura.data.repository.GeneratedWallpaperBackend
import com.chloemlla.aura.data.repository.StabilityGeneratedWallpaperBackend
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GeneratedWallpaperModule {
    @Binds
    @Singleton
    abstract fun bindGeneratedWallpaperBackend(
        backend: StabilityGeneratedWallpaperBackend,
    ): GeneratedWallpaperBackend
}

@Module
@InstallIn(SingletonComponent::class)
object StabilityAiNetworkModule {
    @Provides
    @Singleton
    fun provideStabilityAiApi(client: OkHttpClient): StabilityAiApi =
        Retrofit.Builder()
            .baseUrl(StabilityAiApi.BASE_URL)
            .client(
                client.newBuilder()
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build(),
            )
            .build()
            .create(StabilityAiApi::class.java)
}
