package com.example.withcrossdemo.domain.inference

import android.app.Application
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.InterpreterApi
import java.nio.MappedByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TfliteModelManager @Inject constructor(
    @ApplicationContext private val app: Application
) {
    private val cache = mutableMapOf<String, InterpreterApi>()

    suspend fun getInterpreter(cfg: ModelConfig): InterpreterApi =
        cache[cfg.assetPath] ?: load(cfg).also { cache[cfg.assetPath] = it }

    private suspend fun load(cfg: ModelConfig): InterpreterApi = withContext(Dispatchers.IO) {
        val fd = app.assets.openFd(cfg.assetPath)
        app.assets.openFd(cfg.assetPath).use { fd ->
            val mapped: MappedByteBuffer = java.io.FileInputStream(fd.fileDescriptor).channel
                .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            InterpreterApi.create(mapped, InterpreterApi.Options().setNumThreads(2))
        }
    }

    fun closeAll() { cache.values.forEach { it.close() }; cache.clear() }
}
