plugins {
    id("com.google.gms.google-services") version "4.4.4" apply false
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
}

// The former app/build resource JAR is locked on this Windows workspace.
// Use the verified output directory for both Android Studio and CLI builds.
allprojects {
    layout.buildDirectory.set(layout.projectDirectory.dir("build-development-auth"))
}
