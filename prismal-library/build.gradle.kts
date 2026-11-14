import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

group = "com.github.styropyr0"

android {
    namespace = "com.styropyr0.prismal"
    // Keep this module aligned with the Android SDK platform used by the app and CI.
    compileSdk = 36

    defaultConfig {
        minSdk = 25
        consumerProguardFiles("../external/PrismalAGSL/Prismal/consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../external/PrismalAGSL/Prismal/src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("../external/PrismalAGSL/Prismal/src/main/java"))
            assets.setSrcDirs(listOf("../external/PrismalAGSL/Prismal/src/main/assets"))
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    api(composeBom)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.runtime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.startup.runtime)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("kotlin.time.ExperimentalTime")
    }
}
