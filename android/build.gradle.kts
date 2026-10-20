import java.util.Properties
import javax.inject.Inject
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.ktfmt)
}

repositories {
  google()
  mavenCentral()
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

// Bundle docs/MANUAL.md into the APK (and JVM unit-test classpath) at build time.
abstract class CopyManualAssetTask @Inject constructor() : DefaultTask() {
  @get:InputFile abstract val manualFile: RegularFileProperty

  @get:OutputDirectory abstract val assetsOut: DirectoryProperty

  @get:OutputDirectory abstract val testOut: DirectoryProperty

  @TaskAction
  fun copyManual() {
    fun writeTo(root: java.io.File) {
      val dest = root.resolve("docs")
      dest.mkdirs()
      manualFile.get().asFile.copyTo(dest.resolve("MANUAL.md"), overwrite = true)
    }
    writeTo(assetsOut.get().asFile)
    writeTo(testOut.get().asFile)
  }
}

val copyManualAsset =
    tasks.register<CopyManualAssetTask>("copyManualAsset") {
      manualFile.set(rootDir.resolve("../docs/MANUAL.md"))
      assetsOut.set(layout.buildDirectory.dir("generated/manualAsset"))
      testOut.set(layout.buildDirectory.dir("generated/manualTestResources"))
    }

android {
  val androidApiLevel = providers.gradleProperty("androidApiLevel").get().toInt()

  // Victorious Mesh: no native code is built by Gradle (libgojni.so comes
  // prebuilt in libs/libtailscale.aar), so no ndkVersion pin is needed.
  compileSdk = androidApiLevel

  defaultConfig {
    applicationId = "com.victorious.mesh"
    minSdk = 26
    targetSdk = androidApiLevel

    versionCode = computeVersionCode()
    versionName = getVersionProperty("VERSION_LONG")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Only ship the ABIs the AAR is built for (see scripts/build-aar.sh).
    ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

    buildConfigField(
        "boolean",
        "USE_GOOGLE_DNS_FALLBACK",
        getLocalProperty("tailscale.useGoogleDnsFallback", "true"),
    )
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint { warningsAsErrors = true }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  flavorDimensions += "version"
  namespace = "com.tailscale.ipn"

  // JVM unit tests: static File, not a Provider (AGP 9 rejects Provider srcDir).
  sourceSets {
    getByName("test") { resources.srcDir(file("build/generated/manualTestResources")) }
  }

  signingConfigs {
    // Fixed, committed debug keystore so every debug build (local + CI) shares
    // the same signing cert. The fleet pairs EXTERNAL_NODEs by package + cert.
    getByName("debug") {
      storeFile = file("keystore/victorious-mesh-debug.jks")
      storePassword = "android"
      keyAlias = "victoriousmeshdebug"
      keyPassword = "android"
    }
    // Release signing is read ONLY from environment variables / CI secrets.
    // No release keystore is ever committed to this repo.
    val releaseStore = System.getenv("VM_RELEASE_STORE_FILE")
    if (!releaseStore.isNullOrBlank()) {
      create("release") {
        storeFile = file(releaseStore)
        storePassword = System.getenv("VM_RELEASE_STORE_PASSWORD")
        keyAlias = System.getenv("VM_RELEASE_KEY_ALIAS")
        keyPassword = System.getenv("VM_RELEASE_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    debug {
      manifestPlaceholders["leanbackRequired"] = isTV()
      signingConfig = signingConfigs.getByName("debug")
    }

    release {
      manifestPlaceholders["leanbackRequired"] = isTV()
      signingConfig = signingConfigs.findByName("release")
      isMinifyEnabled = true
      isShrinkResources = true

      proguardFiles(
          getDefaultProguardFile("proguard-android-optimize.txt"),
          "proguard-rules.pro",
      )
    }
  }
}

dependencies {
  // Android dependencies.
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.core)
  implementation(libs.androidx.coreKtx)
  implementation(libs.androidx.browser)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.work.runtimeKtx)

  // Kotlin dependencies.
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.room.ktx)
  runtimeOnly(libs.kotlinx.coroutines.android)
  implementation(libs.kotlin.stdlib)
  implementation(libs.kotlin.reflect)

  // Compose dependencies.
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons)
  implementation(libs.androidx.compose.ui)
  debugImplementation(libs.androidx.compose.ui.tooling)
  implementation(libs.androidx.compose.ui.toolingPreview)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.accompanist.permissions)
  implementation(libs.accompanist.systemuicontroller)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.compose.animation)

  // Navigation dependencies.
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.navigation.ui)

  // Supporting libraries.
  implementation(libs.coil.compose)
  implementation(libs.zxing.core)
  implementation(libs.vico.compose)
  implementation(libs.vico.composeM3)

  // Tailscale dependencies.
  implementation(files("libs/libtailscale.aar"))

  // Integration tests.
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.ext.junitKtx)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.junit)

  // Authentication only for tests.
  androidTestImplementation(libs.kotlin.onetimepassword)
  androidTestImplementation(libs.commons.codec)

  // Unit tests.
  testImplementation(libs.junit)
  testImplementation(libs.mockito.core)
  testImplementation(libs.mockito.inline)
  testImplementation(libs.mockito.kotlin)
  testImplementation(libs.kotlinx.coroutines.test)
}

fun getLocalProperty(key: String, defaultValue: String): String {
  return try {
    val properties = Properties()
    project.file("local.properties").inputStream().use(properties::load)
    properties.getProperty(key) ?: defaultValue
  } catch (_: Throwable) {
    defaultValue
  }
}

fun getVersionProperty(key: String): String {
  val versionProperties = Properties()
  project.file("../tailscale.version").inputStream().use(versionProperties::load)

  return versionProperties.getProperty(key).replace(Regex("^\"|\"$"), "")
}

fun computeVersionCode(): Int {
  val baseProperty = project.findProperty("VERSION_CODE_BASE")

  val base = baseProperty?.toString()?.toLong() ?: ((System.currentTimeMillis() / 60_000L) * 10L)

  val platform = if (isTV()) 1 else 0
  return (base + platform).toInt()
}

fun isTV(): Boolean {
  return project.findProperty("PLATFORM")?.toString() == "tv"
}

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(copyManualAsset) { task ->
      task.assetsOut
    }
  }
}

tasks.matching { it.name.startsWith("preBuild") }.configureEach { dependsOn(copyManualAsset) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
  dependsOn(copyManualAsset)
}
tasks.matching { it.name.contains("UnitTest") && it.name.contains("Res") }.configureEach {
  dependsOn(copyManualAsset)
}
tasks.matching { it.name == "test" || it.name.startsWith("test") }.configureEach {
  dependsOn(copyManualAsset)
}
