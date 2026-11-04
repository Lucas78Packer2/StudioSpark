plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    testOptions { unitTests.isReturnDefaultValues = true }
    namespace = "com.aimusicstudio.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.aimusicstudio.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.2.7"
    }
    signingConfigs {
        create("release") {
            storeFile = file("../aimusicstudio-release.jks")
            storePassword = "aimusicstudio"
            keyAlias = "aimusicstudio"
            keyPassword = "aimusicstudio"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    val bom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
