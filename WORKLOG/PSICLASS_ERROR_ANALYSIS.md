# PsiClass Unresolved Reference 오류 분석

## 🔍 오류 정보

**파일**: `src/main/kotlin/org/dev/semaschatbot/CodeIndexingService.kt:11:25`
**오류**: `Kotlin: Unresolved reference 'PsiClass'`

## 📋 원인 분석

### 문제 원인

`PsiClass`는 IntelliJ Platform의 Java PSI API의 일부로, `com.intellij.java` 플러그인에 포함되어 있습니다. 이 클래스를 사용하려면:

1. **컴파일 타임 의존성**: Java 플러그인이 컴파일 시점에 클래스패스에 포함되어야 함
2. **런타임 의존성**: `plugin.xml`에 Java 플러그인 의존성 선언 필요

### 현재 상태

**build.gradle.kts**:
```kotlin
intellijPlatform {
    create("IC", "2024.1")
    bundledPlugin("com.intellij.java") // Java PSI 지원
    bundledPlugin("org.jetbrains.kotlin") // Kotlin PSI 지원
}
```

✅ Java 플러그인이 `bundledPlugin`으로 선언되어 있음

**문제점**:
- `bundledPlugin`은 주로 런타임 의존성을 추가합니다
- 컴파일 타임 의존성으로는 충분하지 않을 수 있습니다
- `plugin.xml`에 명시적 의존성 선언이 필요할 수 있습니다

## 🔧 해결 방법

### 방법 1: plugin.xml에 의존성 추가 (권장)

`src/main/resources/META-INF/plugin.xml` 파일에 Java 플러그인 의존성을 추가:

```xml
<idea-plugin>
    <id>org.dev.semaschatbot</id>
    <name>SEMAS Chatbot</name>
    <!-- 기존 내용 -->
    
    <!-- Java 플러그인 의존성 추가 -->
    <depends>com.intellij.java</depends>
    
    <!-- 기타 내용 -->
</idea-plugin>
```

### 방법 2: build.gradle.kts 수정

`build.gradle.kts`에서 컴파일 타임 의존성을 명시적으로 추가:

```kotlin
dependencies {
    intellijPlatform {
        create("IC", "2024.1")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }
    
    // 컴파일 타임 의존성 명시적 추가 (필요한 경우)
    compileOnly("com.jetbrains.intellij.java:java-psi-api:2024.1")
}
```

**참고**: IntelliJ Platform의 새로운 Gradle 플러그인에서는 일반적으로 `plugin.xml`에 의존성을 선언하는 것이 권장됩니다.

## ✅ 권장 해결 단계

1. **plugin.xml 확인 및 수정**
   - `plugin.xml` 파일 열기
   - `<depends>` 섹션 확인
   - `<depends>com.intellij.java</depends>` 추가 (없는 경우)

2. **프로젝트 재빌드**
   ```bash
   ./gradlew clean build
   ```

3. **IntelliJ IDEA 동기화**
   - File → Sync Project with Gradle Files
   - 또는 Gradle 탭에서 새로고침

4. **오류 확인**
   - 오류가 해결되었는지 확인
   - 여전히 오류가 있으면 방법 2 시도

## 📝 추가 정보

### PsiClass 사용 위치

`CodeIndexingService.kt`에서 `PsiClass`가 사용되는 위치:

1. **Import 문** (11번째 줄):
   ```kotlin
   import com.intellij.psi.PsiClass
   ```

2. **사용 위치**:
   - 252번째 줄: `PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java)`
   - 293번째 줄: `private fun createClassChunk(psiClass: PsiClass, ...)`

### 관련 PSI 클래스

- `PsiClass`: Java 클래스 표현
- `PsiMethod`: Java 메서드 표현
- `PsiField`: Java 필드 표현
- `PsiFile`: 파일 표현 (언어 독립적)

이들은 모두 `com.intellij.java` 플러그인에 포함되어 있습니다.

## 🐛 문제 해결 체크리스트

- [ ] `plugin.xml`에 `<depends>com.intellij.java</depends>` 추가
- [ ] 프로젝트 재빌드 (`./gradlew clean build`)
- [ ] IntelliJ IDEA 프로젝트 동기화
- [ ] 오류 해결 확인
- [ ] 컴파일 성공 확인

---

**작성일**: 2024년
**버전**: 1.0

