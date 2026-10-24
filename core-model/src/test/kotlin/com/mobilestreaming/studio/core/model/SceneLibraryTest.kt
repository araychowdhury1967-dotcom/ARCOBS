package com.mobilestreaming.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SceneLibraryTest {
    @Test fun startersContainRequiredScenes() {
        val names = SceneLibrary.starters().scenes.map { it.name }
        assertEquals(setOf("Starting Soon", "Main Camera", "Interview", "Screen Presentation", "BRB", "Ending Soon"), names.toSet())
    }

    @Test fun duplicateCreatesIndependentSourceIds() {
        val original = SceneLibrary.starters()
        val duplicated = original.duplicateScene(original.activeSceneId)
        assertNotEquals(original.activeScene.sources.first().id, duplicated.activeScene.sources.first().id)
    }

    @Test fun lastSceneCannotBeDeleted() {
        val scene = Scene(name = "Only")
        val library = SceneLibrary(listOf(scene), scene.id)
        assertThrows(IllegalArgumentException::class.java) { library.deleteScene(scene.id) }
    }

    @Test fun sourceMoveIsBounded() {
        val base = SceneLibrary.starters()
        val scene = base.activeScene
        val moved = base.updateActiveScene { it.moveSource(scene.sources.first().id, -10) }
        assertEquals(scene.sources, moved.activeScene.sources)
    }
}
