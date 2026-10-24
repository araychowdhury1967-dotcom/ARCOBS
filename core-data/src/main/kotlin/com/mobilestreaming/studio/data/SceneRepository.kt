package com.mobilestreaming.studio.data

import android.content.Context
import android.util.AtomicFile
import com.mobilestreaming.studio.core.model.Scene
import com.mobilestreaming.studio.core.model.SceneLibrary
import com.mobilestreaming.studio.core.model.SourceCrop
import com.mobilestreaming.studio.core.model.SourceKind
import com.mobilestreaming.studio.core.model.SourceTransform
import com.mobilestreaming.studio.core.model.VisualSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class SceneRepository(context: Context) {
    private val atomicFile = AtomicFile(File(context.applicationContext.filesDir, "profiles/scenes.json"))
    private val mutex = Mutex()
    private val _library = kotlinx.coroutines.flow.MutableStateFlow(readInitial())
    val library: kotlinx.coroutines.flow.StateFlow<SceneLibrary> = _library

    suspend fun update(transform: (SceneLibrary) -> SceneLibrary): SceneLibrary = mutex.withLock {
        val updated = transform(_library.value)
        if (updated != _library.value) {
            withContext(Dispatchers.IO) { write(updated) }
            _library.value = updated
        }
        return@withLock _library.value
    }

    /** Main-thread-only gesture updates; call [persistCurrent] when the gesture finishes. */
    fun updateLocal(transform: (SceneLibrary) -> SceneLibrary) {
        _library.value = transform(_library.value)
    }

    suspend fun persistCurrent() = mutex.withLock {
        withContext(Dispatchers.IO) { write(_library.value) }
    }

    suspend fun reset(): SceneLibrary = update { SceneLibrary.starters() }

    suspend fun exportTo(output: OutputStream) = withContext(Dispatchers.IO) {
        val root = JSONObject()
            .put("format", PROFILE_FORMAT)
            .put("version", PROFILE_VERSION)
            .put("library", encodeLibrary(_library.value))
        output.bufferedWriter().use { it.write(root.toString(2)) }
    }

    suspend fun importFrom(input: InputStream): SceneLibrary {
        val text = withContext(Dispatchers.IO) {
            input.readBytesLimited(MAX_PROFILE_BYTES).toString(Charsets.UTF_8)
        }
        val root = JSONObject(text)
        require(root.optString("format") == PROFILE_FORMAT) { "Not a Mobile Streaming Studio scene profile" }
        require(root.optInt("version", -1) == PROFILE_VERSION) { "Unsupported scene profile version" }
        val imported = decodeLibrary(root.getJSONObject("library"))
        mutex.withLock {
            withContext(Dispatchers.IO) { write(imported) }
            _library.value = imported
        }
        return imported
    }

    private fun readInitial(): SceneLibrary = runCatching {
        if (!atomicFile.baseFile.exists()) return@runCatching SceneLibrary.starters()
        atomicFile.openRead().use { input ->
            val text = input.readBytesLimited(MAX_PROFILE_BYTES).toString(Charsets.UTF_8)
            val root = JSONObject(text)
            if (root.has("format")) decodeLibrary(root.getJSONObject("library")) else decodeLibrary(root)
        }
    }.getOrElse { SceneLibrary.starters() }

    private fun write(library: SceneLibrary) {
        atomicFile.baseFile.parentFile?.mkdirs()
        val stream = atomicFile.startWrite()
        try {
            val writer = stream.bufferedWriter()
            writer.write(encodeLibrary(library).toString())
            writer.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun encodeLibrary(library: SceneLibrary) = JSONObject()
        .put("activeSceneId", library.activeSceneId)
        .put("scenes", JSONArray().apply {
            library.scenes.forEach { scene ->
                put(JSONObject()
                    .put("id", scene.id)
                    .put("name", scene.name)
                    .put("sources", JSONArray().apply {
                        scene.sources.forEach { source -> put(encodeSource(source)) }
                    }))
            }
        })

    private fun decodeLibrary(root: JSONObject): SceneLibrary {
        val scenesJson = root.getJSONArray("scenes")
        require(scenesJson.length() in 1..MAX_SCENES) { "Profile must contain 1–$MAX_SCENES scenes" }
        val scenes = (0 until scenesJson.length()).map { sceneIndex ->
            val scene = scenesJson.getJSONObject(sceneIndex)
            val sourcesJson = scene.optJSONArray("sources") ?: JSONArray()
            require(sourcesJson.length() <= MAX_SOURCES_PER_SCENE) { "Too many sources in scene" }
            Scene(
                id = scene.getString("id").limited(100),
                name = scene.getString("name").limited(100),
                sources = (0 until sourcesJson.length()).mapNotNull { index ->
                    runCatching { decodeSource(sourcesJson.getJSONObject(index)) }.getOrNull()
                },
            )
        }
        val active = root.getString("activeSceneId")
        return SceneLibrary(scenes = scenes, activeSceneId = active)
    }

    private fun encodeSource(source: VisualSource) = JSONObject()
        .put("id", source.id)
        .put("name", source.name)
        .put("kind", source.kind.name)
        .put("visible", source.visible)
        .put("locked", source.locked)
        .put("opacity", source.opacity.toDouble())
        .put("centerX", source.transform.centerX.toDouble())
        .put("centerY", source.transform.centerY.toDouble())
        .put("width", source.transform.width.toDouble())
        .put("height", source.transform.height.toDouble())
        .put("rotation", source.transform.rotationDegrees.toDouble())
        .put("cropLeft", source.crop.left.toDouble())
        .put("cropTop", source.crop.top.toDouble())
        .put("cropRight", source.crop.right.toDouble())
        .put("cropBottom", source.crop.bottom.toDouble())
        .put("text", source.text)
        .put("imageUri", source.imageUri)
        .put("browserUrl", source.browserUrl)
        .put("browserJavaScript", source.browserJavaScript)
        .put("foregroundColor", source.foregroundColor)
        .put("backgroundColor", source.backgroundColor)
        .put("fontScale", source.fontScale.toDouble())
        .put("mirrored", source.mirrored)

    private fun decodeSource(json: JSONObject): VisualSource {
        val transform = SourceTransform(
            centerX = json.optDouble("centerX", 0.5).finiteFloat(),
            centerY = json.optDouble("centerY", 0.5).finiteFloat(),
            width = json.optDouble("width", 1.0).finiteFloat(),
            height = json.optDouble("height", 1.0).finiteFloat(),
            rotationDegrees = json.optDouble("rotation", 0.0).finiteFloat(),
        ).constrained()
        val crop = SourceCrop(
            left = json.optDouble("cropLeft", 0.0).finiteFloat(),
            top = json.optDouble("cropTop", 0.0).finiteFloat(),
            right = json.optDouble("cropRight", 0.0).finiteFloat(),
            bottom = json.optDouble("cropBottom", 0.0).finiteFloat(),
        ).constrained()
        val source = VisualSource(
            id = json.getString("id").limited(100),
            name = json.getString("name").limited(100),
            kind = SourceKind.valueOf(json.getString("kind")),
            visible = json.optBoolean("visible", true),
            locked = json.optBoolean("locked", false),
            opacity = json.optDouble("opacity", 1.0).finiteFloat().coerceIn(0f, 1f),
            transform = transform,
            crop = crop,
            text = json.optString("text").limited(MAX_TEXT),
            imageUri = json.optString("imageUri").takeIf { it.isNotBlank() }?.limited(MAX_URI),
            browserUrl = json.optString("browserUrl").takeIf { it.isNotBlank() }?.limited(MAX_URI),
            browserJavaScript = json.optBoolean("browserJavaScript", false),
            foregroundColor = json.optLong("foregroundColor", 0xFFFFFFFF),
            backgroundColor = json.optLong("backgroundColor", 0xAA101418),
            fontScale = json.optDouble("fontScale", 1.0).finiteFloat().coerceIn(0.25f, 4f),
            mirrored = json.optBoolean("mirrored", false),
        )
        source.browserUrlValidated()
        return source
    }

    private fun Double.finiteFloat(): Float {
        require(isFinite()) { "Non-finite source value" }
        return toFloat()
    }

    private fun String.limited(max: Int): String {
        require(length <= max) { "Profile text field is too long" }
        return this
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Profile exceeds the $limit-byte limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val PROFILE_FORMAT = "mobile-streaming-studio-scenes"
        const val PROFILE_VERSION = 1
        const val MAX_PROFILE_BYTES = 5 * 1024 * 1024
        const val MAX_SCENES = 100
        const val MAX_SOURCES_PER_SCENE = 100
        const val MAX_TEXT = 20_000
        const val MAX_URI = 4_096
    }
}
