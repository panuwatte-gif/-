plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val releaseSequence = (System.getenv("READYPROOF_SEQUENCE") ?: "1").toInt()
android {
    namespace = "io.github.panuwattegif.readyproof"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.panuwattegif.readyproof"
        minSdk = 30
        targetSdk = 34
        versionCode = 100000 + releaseSequence * 2
        versionName = "2.0.$releaseSequence"
    }
    signingConfigs {
        create("original") {
            storeFile = rootProject.file("../readyproof/keystore/readyproof.p12")
            storeType = "PKCS12"
            storePassword = "readyproof"
            keyAlias = "readyproof"
            keyPassword = "readyproof"
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.getByName("original") }
        getByName("debug") { signingConfig = signingConfigs.getByName("original") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation(kotlin("test-junit"))
}
