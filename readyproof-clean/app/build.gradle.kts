plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
android {
    namespace = "io.github.panuwattegif.readyproofclean"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.panuwattegif.readyproofclean"
        minSdk = 30
        targetSdk = 34
        versionCode = 1000 + buildNumber
        versionName = "0.1.$buildNumber"
    }
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("../readyproof/keystore/readyproof.p12")
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
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation(kotlin("test-junit"))
}
