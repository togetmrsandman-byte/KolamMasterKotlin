import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

abstract class VerifyPublishAdMobConfiguration : DefaultTask() {
    @get:Input
    abstract val rewardedAdUnitId: Property<String>

    @get:Input
    abstract val applicationId: Property<String>

    @TaskAction
    fun verify() {
        val missing = buildList {
            val adUnitId = rewardedAdUnitId.get()
            if (!Regex("""ca-app-pub-\d+/\d+""").matches(adUnitId)) {
                add("admob.publishRewardedAdUnitId")
            }
            val admobApplicationId = applicationId.get()
            if (!Regex("""ca-app-pub-\d+~\d+""").matches(admobApplicationId)) {
                add("admob.applicationId")
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Set production ${missing.joinToString(" and ")} in local.properties before building release."
            )
        }
    }
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val supabasePublishableKey = localProperties.getProperty("supabase.publishableKey")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: throw GradleException(
        "Missing supabase.publishableKey in local.properties. Add the project's Publishable key locally."
    )
val configuredPublishRewardedAdUnitId = localProperties.getProperty("admob.publishRewardedAdUnitId")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val configuredAdMobApplicationId = localProperties.getProperty("admob.applicationId")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val testPublishRewardedAdUnitId = "ca-app-pub-3940256099942544/5224354917"
val testAdMobApplicationId = "ca-app-pub-3940256099942544~3347511713"

android {
    namespace = "com.kolammaster.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kolammaster.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "PUBLISH_REWARDED_AD_UNIT_ID",
                "\"${configuredPublishRewardedAdUnitId ?: testPublishRewardedAdUnitId}\""
            )
            manifestPlaceholders["admobApplicationId"] =
                configuredAdMobApplicationId ?: testAdMobApplicationId
        }
        release {
            buildConfigField(
                "String",
                "PUBLISH_REWARDED_AD_UNIT_ID",
                "\"${configuredPublishRewardedAdUnitId.orEmpty()}\""
            )
            manifestPlaceholders["admobApplicationId"] =
                configuredAdMobApplicationId.orEmpty()
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "SUPABASE_URL", "\"https://myhjakiptwafudgaoura.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabasePublishableKey\"")
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"350376338634-g2d8t3depg7qmfstqp1dv19ferauhiit.apps.googleusercontent.com\""
        )
    }
}

val verifyPublishAdMobRelease = tasks.register<VerifyPublishAdMobConfiguration>(
    "verifyPublishAdMobRelease"
) {
    rewardedAdUnitId.set(configuredPublishRewardedAdUnitId.orEmpty())
    applicationId.set(configuredAdMobApplicationId.orEmpty())
}
tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(verifyPublishAdMobRelease)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.android)
    implementation(libs.play.services.auth)
    implementation(libs.play.services.ads)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.shortcut.badger)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20250517")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}