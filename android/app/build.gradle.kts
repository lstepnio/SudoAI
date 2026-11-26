plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "com.majjix.sudoai"
 compileSdk = 36
 defaultConfig { applicationId = "com.majjix.sudoai"; minSdk = 33; targetSdk = 36; versionCode = 5; versionName = "0.1.4-pilot"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

 signingConfigs {
   create("pilot") {
     val path = System.getenv("SUDOAI_KEYSTORE")
     if (path != null) { storeFile = file(path); storePassword = System.getenv("SUDOAI_STORE_PASSWORD"); keyAlias = System.getenv("SUDOAI_KEY_ALIAS"); keyPassword = System.getenv("SUDOAI_KEY_PASSWORD") }
   }
 }
 buildTypes { release { signingConfig = if (System.getenv("SUDOAI_KEYSTORE") != null) signingConfigs.getByName("pilot") else null; isMinifyEnabled = false } }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2026.05.00"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.core:core-ktx:1.16.0")
 implementation("com.google.mlkit:text-recognition:16.0.1")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 androidTestImplementation(platform("androidx.compose:compose-bom:2026.05.00"))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
