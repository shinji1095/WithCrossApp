package com.example.withcrossdemo.di

import com.example.withcrossdemo.network.WsServerManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideWsServerManager(): WsServerManager = WsServerManager()
}
