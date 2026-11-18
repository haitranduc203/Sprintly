import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull?.reader()?.use(::load)
}
val apiBaseUrl = providers.gradleProperty("sprintlyApiBaseUrl").orNull
    ?.trim().takeUnless { it.isNullOrEmpty() }
    ?: localProperties.getProperty("sprintlyApiBaseUrl")?.trim().takeUnless { it.isNullOrEmpty() }
val apiBaseUri = apiBaseUrl?.let {
    val uri = runCatching { URI(it).parseServerAuthority() }.getOrNull()
    require(uri != null && uri.host != null && uri.port in -1..65535 && uri.port != 0 &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null &&
        (uri.scheme == "https" || uri.scheme == "http" &&
            uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))
    ) {
        "sprintlyApiBaseUrl must be HTTPS or point to a local HTTP development server"
    }
    uri
}

android {
    namespace = "com.example.sprintly"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.sprintly"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        debug {
            val baseUrl = apiBaseUri?.toASCIIString() ?: "http://10.0.2.2:3000/api/v1/"
            buildConfigField("String", "API_BASE_URL", "\"${baseUrl.trimEnd('/')}/\"")
        }
        release {
            val baseUrl = apiBaseUri?.takeIf { it.scheme == "https" }?.toASCIIString()
                ?.let { "${it.trimEnd('/')}/" }.orEmpty()
            buildConfigField("String", "API_BASE_URL", "\"$baseUrl\"")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.room.runtime)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.okhttp.core)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.gson)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
