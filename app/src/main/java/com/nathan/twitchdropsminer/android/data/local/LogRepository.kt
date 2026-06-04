package com.nathan.twitchdropsminer.android.data.local

import android.content.Context
import com.nathan.twitchdropsminer.android.data.model.LocalLogEntry
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val DefaultMaxLogLines = 250

class LogRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxLines: Int = DefaultMaxLogLines,
) {
    private val logFile: File = File(context.applicationContext.filesDir, "android-runtime.log")
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<LocalLogEntry>>(emptyList())

    val entries: StateFlow<List<LocalLogEntry>> = _entries

    suspend fun load() = mutex.withLock {
        val loaded = withContext(ioDispatcher) {
            if (!logFile.exists()) {
                emptyList()
            } else {
                logFile.readLines().map(LocalLogEntry::fromLine)
            }
        }
        _entries.value = BoundedLogBuffer.trim(loaded, maxLines)
    }

    suspend fun append(level: String, message: String) = mutex.withLock {
        val entry = LocalLogEntry(Instant.now(), level.uppercase(), message)
        val updated = BoundedLogBuffer.trim(_entries.value + entry, maxLines)
        _entries.value = updated
        persist(updated)
    }

    suspend fun clear() = mutex.withLock {
        _entries.value = emptyList()
        withContext(ioDispatcher) {
            if (logFile.exists()) {
                logFile.delete()
            }
        }
    }

    fun visibleText(): String =
        entries.value.joinToString(separator = "\n") { it.toLine() }

    private suspend fun persist(entries: List<LocalLogEntry>) {
        withContext(ioDispatcher) {
            logFile.parentFile?.mkdirs()
            logFile.writeText(entries.joinToString(separator = "\n") { it.toLine() })
        }
    }
}

object BoundedLogBuffer {
    fun <T> trim(entries: List<T>, maxLines: Int): List<T> =
        if (maxLines <= 0) emptyList() else if (entries.size <= maxLines) entries else entries.takeLast(maxLines)
}
