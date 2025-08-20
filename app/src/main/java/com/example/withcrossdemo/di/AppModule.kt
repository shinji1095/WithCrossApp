package com.example.withcrossdemo.di

import android.content.Context
import com.example.withcrossdemo.data.ble.BleManager
import com.example.withcrossdemo.data.local.csv.CsvRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideBleManager(@ApplicationContext ctx: Context) = BleManager(ctx)

    @Provides @Singleton
    fun provideCsvRepository(@ApplicationContext ctx: Context) = CsvRepository(ctx)
}
