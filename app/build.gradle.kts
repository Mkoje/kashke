

import java.util.Properties
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

// Local/system notifications work before Firebase is configured.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

fun localProperty(name: String): String? = localProperties.getProperty(name)?.takeIf { it.isNotBlank() }

val supabaseUrl = providers.gradleProperty("SUPABASE_URL")
    .orElse(providers.environmentVariable("SUPABASE_URL"))
    .orElse(providers.provider { localProperty("SUPABASE_URL") ?: "" })
    .getOrElse("")
val supabaseAnonKey = providers.gradleProperty("SUPABASE_ANON_KEY")
    .orElse(providers.environmentVariable("SUPABASE_ANON_KEY"))
    .orElse(providers.provider { localProperty("SUPABASE_ANON_KEY") ?: "" })
    .getOrElse("")
// Fail safely before embedding a server credential into an APK.
val keyRole = runCatching {
    val payload = supabaseAnonKey.split('.').getOrNull(1) ?: ""
    String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
}.getOrDefault("")
require(!supabaseAnonKey.trim().startsWith("sb_secret_") && !Regex("\"role\"\\s*:\\s*\"service_role\"").containsMatchIn(keyRole)) {
    "SUPABASE_ANON_KEY must be a public/publishable key. Server credentials cannot be used in Android."
}
fun buildConfigString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.kashke.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kashke.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SUPABASE_URL", buildConfigString(supabaseUrl))
        buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(supabaseAnonKey))

        testInstrumentationRunner = "com.kashke.app.NotificationHarness"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-client-mock:2.3.12")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("io.github.jan-tennert.supabase:gotrue-kt:2.6.1")
    implementation("io.github.jan-tennert.supabase:postgrest-kt:2.6.1")
    implementation("io.github.jan-tennert.supabase:realtime-kt:2.6.1")
    implementation("io.ktor:ktor-client-android:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
