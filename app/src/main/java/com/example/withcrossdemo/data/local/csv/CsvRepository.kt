package com.example.withcrossdemo.data.local.csv

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CsvRepository @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    suspend fun save(uri: Uri, lines: List<String>) = withContext(Dispatchers.IO) {
        ctx.contentResolver.openOutputStream(uri)?.bufferedWriter().use { w ->
            lines.forEach { w?.appendLine(it) }
        }
    }
}
