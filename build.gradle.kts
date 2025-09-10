import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.4.0"
}

group = "org.dev"
version = "0.0.8"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        localPlatformArtifacts()
    }
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("IC", "2024.1")
        bundledPlugin("com.intellij.java") // Java PSI 지원
        bundledPlugin("org.jetbrains.kotlin") // Kotlin PSI 지원
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }
    implementation("org.json:json:20250517")  // 최신 버전 추가
    implementation("com.google.code.gson:gson:2.13.1")  // Gson 최신 버전 추가
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("lib/tibero7-jdbc.jar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
        }

        changeNotes = """
      Initial version
    """.trimIndent()
    }
    pluginVerification {
        ides {
            // 옵션 1: recommended() - 자동으로 현재 플러그인 호환 IDE 추천 (가장 간단)
            //recommended()

            // 옵션 2: 특정 IDE 지정 (예: IntelliJ IntellijIdeaUltimate 2025.1)
            //ide(IntelliJPlatformType.IntellijIdeaUltimate, "2025.1")

            // 옵션 3: 로컬 IDE 경로 지정 (로컬 설치된 IDE 사용 시)
            //local(file("C:\\Program Files\\JetBrains\\IntelliJ IDEA 2025.1.3"))  // 실제 경로로 변경

            // 옵션 4: 필터링으로 여러 IDE 선택
            select {
                types = listOf(IntelliJPlatformType.IntellijIdeaUltimate)
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = "241.*"
                untilBuild = "251.*"
            }
        }
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    patchPluginXml {
        sinceBuild.set("241") // 2024.1 버전부터 호환
        untilBuild.set("251.*") // 2025.1 버전의 모든 릴리스까지 호환
    }
}
