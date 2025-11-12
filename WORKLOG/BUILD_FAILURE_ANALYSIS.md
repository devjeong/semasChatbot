# 빌드 실패 원인 분석 및 해결

## 🔍 오류 분석

### 오류 메시지

```
Caused by: java.lang.ClassNotFoundException: junit.framework.TestCase
Caused by: java.util.ServiceConfigurationError: org.junit.platform.launcher.LauncherSessionListener: 
Provider com.intellij.tests.JUnit5TestSessionListener could not be instantiated
```

### 원인

1. **IntelliJ Platform 테스트 프레임워크와 JUnit 5 충돌**
   - `build.gradle.kts`에서 `testFramework(TestFrameworkType.Platform)`를 사용하고 있었음
   - IntelliJ Platform의 테스트 프레임워크는 JUnit 4 (`junit.framework.TestCase`)에 의존함
   - 하지만 우리는 JUnit 5만 추가했기 때문에 클래스를 찾을 수 없음

2. **테스트 클래스 인식 문제**
   - `GeminiApiEndpointManualTest`는 실제 테스트가 아닌 수동 실행 스크립트인데 테스트로 인식됨
   - `object` 클래스이지만 테스트 메서드가 없어도 테스트 실행 시도

## ✅ 해결 방법

### 1. IntelliJ Platform 테스트 프레임워크 비활성화

`build.gradle.kts`에서 다음 줄을 주석 처리:

```kotlin
// testFramework는 주석 처리 - 표준 JUnit 5 사용
// testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
```

### 2. ManualTest 클래스 테스트에서 제외

`build.gradle.kts`의 테스트 설정에 제외 패턴 추가:

```kotlin
withType<Test> {
    useJUnitPlatform()
    // 테스트에서 제외할 패턴 (ManualTest는 테스트가 아님)
    exclude("**/GeminiApiEndpointManualTest.class")
}
```

## 📋 변경 사항 요약

### build.gradle.kts 변경

**변경 전**:
```kotlin
intellijPlatform {
    create("IC", "2024.1")
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.kotlin")
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
}

tasks {
    withType<Test> {
        useJUnitPlatform()
    }
}
```

**변경 후**:
```kotlin
intellijPlatform {
    create("IC", "2024.1")
    bundledPlugin("com.intellij.java")
    bundledPlugin("org.jetbrains.kotlin")
    // testFramework는 주석 처리 - 표준 JUnit 5 사용
    // testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
}

tasks {
    withType<Test> {
        useJUnitPlatform()
        // 테스트에서 제외할 패턴 (ManualTest는 테스트가 아님)
        exclude("**/GeminiApiEndpointManualTest.class")
    }
}
```

## 🧪 테스트 실행 확인

변경 후 다음 명령어로 테스트 실행:

```bash
./gradlew test --tests GeminiApiEndpointTest
```

또는 IntelliJ IDEA에서:
1. `GeminiApiEndpointTest.kt` 파일 열기
2. 테스트 메서드 옆의 실행 버튼 클릭

## ⚠️ 주의사항

1. **IntelliJ Platform 테스트 프레임워크 사용 시**
   - 플러그인 내부 테스트를 작성할 때는 IntelliJ Platform 테스트 프레임워크를 사용하는 것이 좋습니다
   - 하지만 외부 API 테스트(우리 경우)는 표준 JUnit 5가 더 적합합니다

2. **ManualTest 클래스**
   - `GeminiApiEndpointManualTest`는 테스트가 아닌 수동 실행 스크립트입니다
   - `main` 함수로 직접 실행해야 합니다
   - 테스트 실행에서 제외되어야 합니다

## 🔄 대안 해결 방법

만약 IntelliJ Platform 테스트 프레임워크를 계속 사용해야 한다면:

1. **JUnit 4 의존성 추가**:
```kotlin
testImplementation("junit:junit:4.13.2")
```

하지만 이 방법은 권장하지 않습니다. 표준 JUnit 5를 사용하는 것이 더 현대적이고 유지보수가 쉽습니다.

---

**작성일**: 2024년
**버전**: 1.0

