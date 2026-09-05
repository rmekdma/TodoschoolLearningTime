plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.todoschooltime"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.todoschooltime"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
}

