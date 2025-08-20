package com.example.withcrossdemo.di

import android.app.Application
import com.example.withcrossdemo.domain.inference.TfliteModelManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MlModule {

    @Provides @Singleton
    fun provideTfliteModelManager(app: Application) = TfliteModelManager(app)
}
