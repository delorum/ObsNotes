plugins { id("com.android.application") }
android {
 namespace = "ru.obsnotes"
 compileSdk { version = release(36) { minorApiLevel = 1 } }
 defaultConfig { applicationId = "ru.obsnotes"; minSdk = 33; targetSdk = 36; versionCode = 46; versionName = "0.2.2" }
 buildFeatures { buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
 buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
 coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
 implementation(project(":core"))
 implementation("androidx.core:core-ktx:1.12.0")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
 implementation("androidx.constraintlayout:constraintlayout:2.1.4")
 implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
 implementation("androidx.appcompat:appcompat:1.6.1")
 implementation("com.google.android.material:material:1.10.0")
 implementation("androidx.documentfile:documentfile:1.0.0")
}
