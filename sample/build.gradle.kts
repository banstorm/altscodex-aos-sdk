import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // 루트 build.gradle.kts 가 같은 AGP·Kotlin 플러그인을 이미 클래스패스에 올리므로 버전 없이 적용한다.
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// client_id·redirect URI 는 커밋하지 않는 루트 local.properties 에서 읽는다. 없으면 빈 값으로 빌드되고 앱이 안내를 띄운다.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
val clientId: String = localProperties.getProperty("altscodex.clientId", "").trim()
val redirectUri: String = localProperties.getProperty("altscodex.redirectUri", "").trim()
// AppAuth 매니페스트는 스킴이 비어 있으면 안 되므로 값이 없을 때는 패키지명을 대신 넣는다.
val redirectScheme: String = redirectUri.substringBefore(':', "").ifBlank { "com.altscodex.sample" }

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.altscodex.sample"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.altscodex.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "ALTSCODEX_CLIENT_ID", quoted(clientId))
        buildConfigField("String", "ALTSCODEX_REDIRECT_URI", quoted(redirectUri))
        manifestPlaceholders["appAuthRedirectScheme"] = redirectScheme
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":sdk"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
