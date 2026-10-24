buildscript {
    configurations.classpath {
        // Kotlin 1.9.x is compatible with this newer JavaPoet release.
        resolutionStrategy.force("com.squareup:javapoet:1.13.0")
    }
}

plugins {
    id("com.android.application") version "8.9.0" apply false
    id("com.android.library") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
}
