import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.isFile) {
        propsFile.inputStream().use { load(it) }
    }
}

fun unquoteLocalProperty(value: String): String =
    value.trim().removeSurrounding("\"").removeSurrounding("'")

fun localProperty(name: String): String? =
    localProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

fun signingProperty(name: String): String? = localProperty(name)

fun quotedBuildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

/** Release 签名配置问题说明；返回 null 表示可正常签名 */
fun releaseSigningConfigError(): String? {
    val storePath = signingProperty("RELEASE_STORE_FILE")
    val storePassword = signingProperty("RELEASE_STORE_PASSWORD")
    val keyAlias = signingProperty("RELEASE_KEY_ALIAS")
    val keyPassword = signingProperty("RELEASE_KEY_PASSWORD") ?: storePassword

    val missing = mutableListOf<String>()
    if (storePath == null) missing += "RELEASE_STORE_FILE"
    if (storePassword == null) missing += "RELEASE_STORE_PASSWORD"
    if (keyAlias == null) missing += "RELEASE_KEY_ALIAS"
    if (keyPassword == null) missing += "RELEASE_KEY_PASSWORD（或 RELEASE_STORE_PASSWORD）"
    if (missing.isNotEmpty()) {
        return buildString {
            appendLine("Release 签名未配置完整，请在 local.properties 中设置：")
            missing.forEach { appendLine("  - $it") }
            append("参考 signing.properties.example / local.properties.example")
        }
    }

    val store = rootProject.file(storePath!!)
    if (!store.isFile) {
        return "Release keystore 文件不存在：${store.absolutePath}\n请检查 local.properties 中的 RELEASE_STORE_FILE"
    }
    return null
}

fun requireReleaseSigningConfigured() {
    val err = releaseSigningConfigError()
    if (err != null) {
        error(err)
    }
    val releaseBuildType = android.buildTypes.getByName("release")
    if (releaseBuildType.signingConfig == null) {
        error("Release 未关联签名配置，无法生成可分发 APK/AAB。\n${releaseSigningConfigError() ?: "检查 signingConfigs.release"}")
    }
}

android {
    signingConfigs {
        if (releaseSigningConfigError() == null) {
            val storePath = signingProperty("RELEASE_STORE_FILE")!!
            val storePassword = signingProperty("RELEASE_STORE_PASSWORD")!!
            val keyAlias = signingProperty("RELEASE_KEY_ALIAS")!!
            val keyPassword = signingProperty("RELEASE_KEY_PASSWORD")
                ?: signingProperty("RELEASE_STORE_PASSWORD")!!
            create("release") {
                storeFile = rootProject.file(storePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    namespace = "com.openvpn.client"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.openvpn.client"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val appSecret = unquoteLocalProperty(localProperty("APP_SECRET") ?: "")
        buildConfigField(
            "String",
            "APP_SECRET",
            quotedBuildConfigString(appSecret),
        )
    }

    buildTypes {
        debug {
            // 在 local.properties 配置 DEBUG_API_BASE_URL（见 local.properties.example）。
            // 默认 10.0.2.2；部分 AVD 上 OkHttp 会超时，可改为电脑局域网 IP 或 127.0.0.1 + adb reverse。
            val debugApiBaseUrl = localProperty("DEBUG_API_BASE_URL") ?: "http://10.0.2.2:3000/api"
            buildConfigField("String", "API_BASE_URL", quotedBuildConfigString(debugApiBaseUrl))
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "API_BASE_URL", quotedBuildConfigString("https://www.openshopx.xyz/api"))
            // 真机分发：不打包 x86/x86_64（模拟器 ABI），约可减少 60MB+ native 体积
            ndk {
                abiFilters.clear()
                abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":vpn-engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.viewpager2)
    implementation(libs.recyclerview)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.multidex)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.work.multiprocess)
    implementation(libs.work.runtime.ktx)
    implementation(libs.mmkv.static)
    implementation(libs.androidx.security.crypto)
    implementation(libs.toasty)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

val releaseArtifactsDir = rootProject.layout.projectDirectory.dir("release-artifacts")

fun releaseApkFileName(): String =
    "openvpn_${android.defaultConfig.versionName}.apk"

fun registerArchiveMappingTask(
    taskName: String,
    description: String,
    assembleTask: String,
) {
    tasks.register<Copy>(taskName) {
        group = "release"
        this.description = description
        dependsOn(assembleTask)
        val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
        from(mappingFile)
        into(releaseArtifactsDir)
        val versionName = android.defaultConfig.versionName
        val versionCode = android.defaultConfig.versionCode
        rename { "openvpn-${versionName}-${versionCode}-mapping.txt" }
        doFirst {
            if (!mappingFile.get().asFile.isFile) {
                error("未找到 mapping.txt，请先执行 ./gradlew :app:$assembleTask（且 release 已开启 isMinifyEnabled）")
            }
        }
        doLast {
            val dest = releaseArtifactsDir.asFile.resolve("openvpn-${versionName}-${versionCode}-mapping.txt")
            logger.lifecycle("已归档 R8 mapping：${dest.absolutePath}")
            logger.lifecycle("请与对应 APK/AAB 一并保存，用于解析线上崩溃堆栈。")
        }
    }
}

registerArchiveMappingTask(
    taskName = "archiveReleaseMapping",
    description = "将 R8 mapping.txt 复制到 release-artifacts/（依赖 assembleRelease）",
    assembleTask = "assembleRelease",
)

registerArchiveMappingTask(
    taskName = "archiveBundleReleaseMapping",
    description = "将 R8 mapping.txt 复制到 release-artifacts/（依赖 bundleRelease）",
    assembleTask = "bundleRelease",
)

afterEvaluate {
    listOf("assembleRelease", "bundleRelease").forEach { taskName ->
        tasks.named(taskName).configure {
            doFirst { requireReleaseSigningConfigured() }
        }
    }

    tasks.register("renameReleaseApk") {
        group = "release"
        description = "将 Release APK 重命名为 openvpn_{versionName}.apk"
        doLast {
            val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val dest = releaseDir.resolve(releaseApkFileName())
            val versionName = android.defaultConfig.versionName
            val candidates = listOf(
                "app-release.apk",
                "openvpn_${versionName}-release.apk",
            ).map { releaseDir.resolve(it) }

            val src = candidates.firstOrNull { it.isFile }
                ?: releaseDir.listFiles()
                    ?.firstOrNull { it.isFile && it.extension == "apk" && it.name != dest.name }

            if (dest.isFile && src == null) {
                logger.lifecycle("Release APK：${dest.absolutePath}")
            } else if (src == null || !src.isFile) {
                error("未找到 release APK，请先成功执行 assembleRelease")
            } else if (src.absolutePath != dest.absolutePath) {
                dest.delete()
                if (!src.renameTo(dest)) {
                    src.copyTo(dest, overwrite = true)
                    src.delete()
                }
                logger.lifecycle("Release APK：${dest.absolutePath}")
            }

            releaseArtifactsDir.asFile.mkdirs()
            dest.copyTo(releaseArtifactsDir.asFile.resolve(releaseApkFileName()), overwrite = true)
            logger.lifecycle(
                "已归档 Release APK：${releaseArtifactsDir.asFile.resolve(releaseApkFileName()).absolutePath}",
            )
        }
    }

    tasks.named("assembleRelease").configure {
        finalizedBy("renameReleaseApk")
    }
}

tasks.register("releaseApk") {
    group = "release"
    description = "构建 Release APK（openvpn_{versionName}.apk）并归档 mapping.txt"
    dependsOn("assembleRelease", "renameReleaseApk", "archiveReleaseMapping")
}

tasks.register("releaseBundle") {
    group = "release"
    description = "构建 Release AAB 并归档 mapping.txt"
    dependsOn("bundleRelease", "archiveBundleReleaseMapping")
}
