plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.todoschooltime.e2eobserver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.todoschooltime.e2eobserver"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
