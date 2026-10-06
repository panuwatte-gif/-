plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes its run number so every published build installs over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "io.github.panuwattegif.readyproof"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.panuwattegif.readyproof"
        // Android 11+: needed for silent screenshots from the accessibility service.
        minSdk = 30
        targetSdk = 34
        versionCode = 100 + buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // One fixed key for every build, so a new APK always installs over the old one
        // (keeps settings and the accessibility permission). See keystore/README.md.
        create("shared") {
            storeFile = rootProject.file("keystore/readyproof.p12")
            storeType = "PKCS12"
            storePassword = "readyproof"
            keyAlias = "readyproof"
            keyPassword = "readyproof"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation(kotlin("test-junit"))
}
