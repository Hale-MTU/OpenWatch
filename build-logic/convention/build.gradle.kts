plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "openwatch.android.application"
            implementationClass = "OpenWatchAndroidApplicationPlugin"
        }
        register("androidLibrary") {
            id = "openwatch.android.library"
            implementationClass = "OpenWatchAndroidLibraryPlugin"
        }
        register("jvmLibrary") {
            id = "openwatch.jvm.library"
            implementationClass = "OpenWatchJvmLibraryPlugin"
        }
    }
}
