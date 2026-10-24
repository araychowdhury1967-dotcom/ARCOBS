package com.mobilestreaming.studio.core.model

import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class SourceKind {
    CAMERA,
    SCREEN,
    IMAGE,
    TEXT,
    LOGO,
    LOWER_THIRD,
    BREAKING_NEWS,
    TICKER,
    CLOCK,
    DATE,
    BROWSER,
    SOLID_COLOR,
}

data class SourceTransform(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val width: Float = 1f,
    val height: Float = 1f,
    val rotationDegrees: Float = 0f,
) {
    fun constrained(): SourceTransform = copy(
        centerX = centerX.coerceIn(-0.5f, 1.5f),
        centerY = centerY.coerceIn(-0.5f, 1.5f),
        width = width.coerceIn(0.02f, 4f),
        height = height.coerceIn(0.02f, 4f),
        rotationDegrees = ((rotationDegrees % 360f) + 360f) % 360f,
    )

    fun translated(dx: Float, dy: Float) = copy(centerX = centerX + dx, centerY = centerY + dy).constrained()

    fun scaled(scale: Float) = copy(
        width = width * scale,
        height = height * scale,
    ).constrained()
}

data class SourceCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    fun constrained(): SourceCrop {
        val l = left.coerceIn(0f, 0.45f)
        val t = top.coerceIn(0f, 0.45f)
        val r = right.coerceIn(0f, 0.45f)
        val b = bottom.coerceIn(0f, 0.45f)
        return SourceCrop(l, t, min(r, 0.95f - l), min(b, 0.95f - t))
    }
}

data class VisualSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: SourceKind,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    val transform: SourceTransform = SourceTransform(),
    val crop: SourceCrop = SourceCrop(),
    val text: String = "",
    val imageUri: String? = null,
    val browserUrl: String? = null,
    val browserJavaScript: Boolean = false,
    val foregroundColor: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0xAA101418,
    val fontScale: Float = 1f,
    val mirrored: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "Source name cannot be blank" }
        require(opacity in 0f..1f) { "Opacity must be between 0 and 1" }
        require(fontScale in 0.25f..4f) { "Font scale must be between 0.25 and 4" }
    }

    fun browserUrlValidated(): String? {
        if (kind != SourceKind.BROWSER || browserUrl == null) return null
        val uri = java.net.URI(browserUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "Browser overlays require an HTTPS URL with a valid host"
        }
        require(uri.userInfo == null && uri.fragment == null) { "Browser overlay URL contains unsupported components" }
        return uri.toASCIIString()
    }
}

data class Scene(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sources: List<VisualSource> = emptyList(),
) {
    init { require(name.isNotBlank()) { "Scene name cannot be blank" } }

    val orderedSources: List<VisualSource> get() = sources

    fun withSource(source: VisualSource): Scene = copy(sources = sources + source)

    fun updateSource(id: String, update: (VisualSource) -> VisualSource): Scene = copy(
        sources = sources.map { if (it.id == id) update(it) else it },
    )

    fun removeSource(id: String): Scene = copy(sources = sources.filterNot { it.id == id })

    fun moveSource(id: String, delta: Int): Scene {
        val from = sources.indexOfFirst { it.id == id }
        if (from < 0) return this
        val to = (from + delta).coerceIn(sources.indices)
        if (from == to) return this
        val mutable = sources.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        return copy(sources = mutable)
    }
}

data class SceneLibrary(
    val scenes: List<Scene>,
    val activeSceneId: String,
    val selectedSourceId: String? = null,
) {
    init {
        require(scenes.isNotEmpty()) { "At least one scene is required" }
        require(scenes.any { it.id == activeSceneId }) { "Active scene must exist" }
        require(scenes.map { it.id }.distinct().size == scenes.size) { "Scene IDs must be unique" }
    }

    val activeScene: Scene get() = scenes.first { it.id == activeSceneId }

    fun switchScene(id: String): SceneLibrary {
        require(scenes.any { it.id == id }) { "Scene does not exist" }
        return copy(activeSceneId = id, selectedSourceId = null)
    }

    fun addScene(name: String): SceneLibrary {
        val scene = Scene(name = uniqueSceneName(name.ifBlank { "Untitled scene" }))
        return copy(scenes = scenes + scene, activeSceneId = scene.id, selectedSourceId = null)
    }

    fun renameScene(id: String, name: String): SceneLibrary {
        require(name.isNotBlank()) { "Scene name cannot be blank" }
        return updateScenes { scene -> if (scene.id == id) scene.copy(name = name.trim()) else scene }
    }

    fun duplicateScene(id: String): SceneLibrary {
        val original = scenes.firstOrNull { it.id == id } ?: return this
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = uniqueSceneName("${original.name} copy"),
            sources = original.sources.map { it.copy(id = UUID.randomUUID().toString()) },
        )
        val index = scenes.indexOfFirst { it.id == id }
        val mutable = scenes.toMutableList().apply { add(index + 1, copy) }
        return copy(scenes = mutable, activeSceneId = copy.id, selectedSourceId = null)
    }

    fun deleteScene(id: String): SceneLibrary {
        require(scenes.size > 1) { "The last scene cannot be deleted" }
        val remaining = scenes.filterNot { it.id == id }
        if (remaining.size == scenes.size) return this
        val nextActive = if (activeSceneId == id) remaining.first().id else activeSceneId
        return copy(scenes = remaining, activeSceneId = nextActive, selectedSourceId = null)
    }

    fun moveScene(id: String, delta: Int): SceneLibrary {
        val from = scenes.indexOfFirst { it.id == id }
        if (from < 0) return this
        val to = (from + delta).coerceIn(scenes.indices)
        if (from == to) return this
        val mutable = scenes.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        return copy(scenes = mutable)
    }

    fun updateActiveScene(update: (Scene) -> Scene): SceneLibrary = updateScenes {
        if (it.id == activeSceneId) update(it) else it
    }

    fun selectSource(id: String?): SceneLibrary = copy(
        selectedSourceId = id?.takeIf { sourceId -> activeScene.sources.any { it.id == sourceId } },
    )

    private fun updateScenes(update: (Scene) -> Scene): SceneLibrary = copy(scenes = scenes.map(update))

    private fun uniqueSceneName(base: String): String {
        var candidate = base.trim()
        var suffix = 2
        while (scenes.any { it.name.equals(candidate, ignoreCase = true) }) {
            candidate = "$base $suffix"
            suffix++
        }
        return candidate
    }

    companion object {
        fun starters(): SceneLibrary {
            val camera = VisualSource(name = "Camera", kind = SourceKind.CAMERA)
            val scenes = listOf(
                Scene(
                    name = "Main Camera",
                    sources = listOf(camera),
                ),
                Scene(
                    name = "Starting Soon",
                    sources = listOf(
                        VisualSource(name = "Background", kind = SourceKind.SOLID_COLOR, backgroundColor = 0xFF111827),
                        VisualSource(
                            name = "Starting title", kind = SourceKind.TEXT, text = "STARTING SOON",
                            fontScale = 1.8f, transform = SourceTransform(width = 0.9f, height = 0.25f),
                        ),
                    ),
                ),
                Scene(
                    name = "Interview",
                    sources = listOf(
                        camera.copy(),
                        VisualSource(
                            name = "Guest lower third", kind = SourceKind.LOWER_THIRD,
                            text = "GUEST NAME  •  TITLE", backgroundColor = 0xE60B7285,
                            transform = SourceTransform(centerX = 0.3f, centerY = 0.82f, width = 0.56f, height = 0.14f),
                        ),
                    ),
                ),
                Scene(
                    name = "Screen Presentation",
                    sources = listOf(
                        VisualSource(name = "Shared screen", kind = SourceKind.SCREEN),
                        VisualSource(
                            name = "Camera PiP", kind = SourceKind.CAMERA,
                            transform = SourceTransform(centerX = 0.84f, centerY = 0.78f, width = 0.25f, height = 0.25f),
                        ),
                    ),
                ),
                Scene(
                    name = "BRB",
                    sources = listOf(
                        VisualSource(name = "Background", kind = SourceKind.SOLID_COLOR, backgroundColor = 0xFF1E1B4B),
                        VisualSource(name = "BRB title", kind = SourceKind.TEXT, text = "BE RIGHT BACK", fontScale = 1.8f),
                    ),
                ),
                Scene(
                    name = "Ending Soon",
                    sources = listOf(
                        VisualSource(name = "Background", kind = SourceKind.SOLID_COLOR, backgroundColor = 0xFF18181B),
                        VisualSource(name = "Ending title", kind = SourceKind.TEXT, text = "THANKS FOR WATCHING", fontScale = 1.5f),
                    ),
                ),
            )
            return SceneLibrary(scenes = scenes, activeSceneId = scenes.first().id)
        }
    }
}

fun VisualSource.normalizedBounds(): FloatArray {
    val halfW = transform.width / 2f
    val halfH = transform.height / 2f
    return floatArrayOf(
        max(-1f, transform.centerX - halfW), max(-1f, transform.centerY - halfH),
        min(2f, transform.centerX + halfW), min(2f, transform.centerY + halfH),
    )
}
