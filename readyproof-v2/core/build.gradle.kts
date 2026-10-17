plugins { id("org.jetbrains.kotlin.jvm") }
dependencies { testImplementation(kotlin("test-junit")) }
kotlin { jvmToolchain(17) }
