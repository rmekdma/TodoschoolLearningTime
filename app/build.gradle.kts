import java.net.URI

plugins {
    id("com.android.application")
}

fun validatedDebugApiBaseUrl(value: String): String {
    val uri = try {
        URI(value)
    } catch (exception: Exception) {
        throw GradleException("learningApiBaseUrl must be an http://127.0.0.1:<port> URL", exception)
    }
    require(
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 &&
            uri.userInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null,
    ) {
        "learningApiBaseUrl must be an http://127.0.0.1:<port> URL with no path, credentials, query, or fragment"
    }
    return value.trimEnd('/')
}

val debugApiBaseUrl = validatedDebugApiBaseUrl(
    providers.gradleProperty("learningApiBaseUrl").orElse("http://127.0.0.1:18765").get(),
)

android {
    namespace = "com.example.todoschooltime"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.example.todoschooltime"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            buildConfigField("String", "API_BASE_URL", "\"https://api.todomath.com\"")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

