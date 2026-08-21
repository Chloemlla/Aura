package com.freevibe.di

import com.freevibe.data.repository.FossGeneratedWallpaperBackend
import com.freevibe.data.repository.GeneratedWallpaperBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GeneratedWallpaperModule {
    @Binds
    @Singleton
    abstract fun bindGeneratedWallpaperBackend(
        backend: FossGeneratedWallpaperBackend,
    ): GeneratedWallpaperBackend
}
