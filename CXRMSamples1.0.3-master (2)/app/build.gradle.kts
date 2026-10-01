import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rokid.cxrmsamples"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.rokid.cxrmsamples"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // netty jars from NLS SDK contain duplicate META-INF/INDEX.LIST
            excludes += "META-INF/INDEX.LIST"
            // netty jars also contain duplicate versions metadata
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
    
    // 禁用重复类检查（高德SDK有重复类，但功能正常）
    // 注意：任务可能在配置阶段不存在，使用afterEvaluate确保任务存在后再禁用
    afterEvaluate {
        tasks.findByName("checkDebugDuplicateClasses")?.enabled = false
        tasks.findByName("checkReleaseDuplicateClasses")?.enabled = false
    }
}

val amapSearch by configurations.creating

val filterAmapSearchJar = tasks.register<Jar>("filterAmapSearchJar") {
    val searchJarProvider = amapSearch.elements.map { it.single().asFile }
    from(searchJarProvider.map { zipTree(it) })
    exclude(
        "com/amap/apis/utils/core/api/AMapUtilCoreApi.class",
        "com/amap/apis/utils/core/api/NetProxy.class"
    )
    archiveFileName.set("search-9.7.1-filtered.jar")
    destinationDirectory.set(layout.buildDirectory.dir("filtered-libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val filteredAmapSearchJar = filterAmapSearchJar.flatMap { it.archiveFile }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation ("com.squareup.retrofit2:retrofit:2.9.0")
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation ("com.squareup.okhttp3:okhttp:4.9.3")
    implementation ("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    implementation ("com.squareup.okio:okio:2.8.0")
    implementation ("com.google.code.gson:gson:2.10.1")
    implementation ("com.squareup.okhttp3:logging-interceptor:4.9.1")

    implementation("com.rokid.cxr:client-m:1.1.0") {
        exclude(group = "com.rokid.cxr", module = "client-m-sources")
    }

    // Alibaba Cloud NLS SDK (升级到 2.2.1 支持 VAD 参数)
    implementation("com.alibaba.nls:nls-sdk-common:2.2.1")
    implementation("com.alibaba.nls:nls-sdk-recognizer:2.2.1")
    implementation("com.alibaba.nls:nls-sdk-tts:2.2.1")
    implementation("com.alibaba.nls:nls-sdk-transcriber:2.2.1")
    implementation("com.aliyun:aliyun-java-sdk-core:3.7.1")
    implementation("com.alibaba:fastjson:1.2.49")

    // ONNX Runtime for local semantic routing
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")

    // 高德SDK（定位+搜索）
    // 通过过滤search jar内重复类避免D8重复类错误
    implementation("com.amap.api:location:6.5.1")
    amapSearch("com.amap.api:search:9.7.1")
    implementation(files(filteredAmapSearchJar).builtBy(filterAmapSearchJar))

}