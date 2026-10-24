package com.mobilestreaming.studio.data

import android.content.Context
import android.util.AtomicFile
import com.mobilestreaming.studio.core.model.RecordingEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RecordingRepository(context: Context) {
    private val atomicFile = AtomicFile(File(context.applicationContext.filesDir, "recordings/history.json"))
    private val mutex = Mutex()
    private val _recordings = kotlinx.coroutines.flow.MutableStateFlow(readInitial())
    val recordings: kotlinx.coroutines.flow.StateFlow<List<RecordingEntry>> = _recordings

    suspend fun add(entry: RecordingEntry) = mutex.withLock {
        val updated = (listOf(entry) + _recordings.value.filterNot { it.id == entry.id }).take(MAX_HISTORY)
        persist(updated)
        _recordings.value = updated
    }

    suspend fun remove(id: String) = mutex.withLock {
        val updated = _recordings.value.filterNot { it.id == id }
        persist(updated)
        _recordings.value = updated
    }

    suspend fun clearMissing(existingUris: Set<String>) = mutex.withLock {
        val updated = _recordings.value.filter { it.contentUri in existingUris }
        if (updated != _recordings.value) {
            persist(updated)
            _recordings.value = updated
        }
    }

    private fun readInitial(): List<RecordingEntry> = runCatching {
        if (!atomicFile.baseFile.exists()) return@runCatching emptyList()
        atomicFile.openRead().use { input ->
            val array = JSONArray(input.readBytes().toString(Charsets.UTF_8))
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                RecordingEntry(
                    id = item.getString("id"),
                    displayName = item.getString("displayName"),
                    contentUri = item.getString("contentUri"),
                    createdAtEpochMs = item.getLong("createdAt"),
                    durationMs = item.getLong("durationMs"),
                    sizeBytes = item.getLong("sizeBytes"),
                    width = item.getInt("width"),
                    height = item.getInt("height"),
                )
            }
        }
    }.getOrElse { emptyList() }

    private suspend fun persist(entries: List<RecordingEntry>) = withContext(Dispatchers.IO) {
        atomicFile.baseFile.parentFile?.mkdirs()
        val stream = atomicFile.startWrite()
        try {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(JSONObject()
                    .put("id", entry.id)
                    .put("displayName", entry.displayName)
                    .put("contentUri", entry.contentUri)
                    .put("createdAt", entry.createdAtEpochMs)
                    .put("durationMs", entry.durationMs)
                    .put("sizeBytes", entry.sizeBytes)
                    .put("width", entry.width)
                    .put("height", entry.height))
            }
            val writer = stream.bufferedWriter()
            writer.write(array.toString())
            writer.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private companion object { const val MAX_HISTORY = 500 }
}
