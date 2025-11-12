# 테스트 실행 가이드

## 🚀 빠른 시작

### 1단계: 환경 확인

```bash
# Java 버전 확인
java -version

# Gradle 확인
gradlew.bat --version
```

### 2단계: API Key 설정

`src/test/kotlin/org/dev/semaschatbot/GeminiApiEndpointTest.kt` 파일을 열고:

```kotlin
private val testApiKey = "YOUR_GEMINI_API_KEY_HERE"
```

실제 API Key로 변경:
```kotlin
private val testApiKey = "AIzaSyCbHuQC9T3iMGAkbZhw7EHNSFRi2WH7z4U"
```

### 3단계: 서버 실행 확인

중간 서버가 `localhost:5000`에서 실행 중인지 확인:

```bash
# PowerShell
Test-NetConnection -ComputerName localhost -Port 5000

# 또는 브라우저에서
# http://localhost:5000/api/gemini (GET 요청은 실패할 수 있지만 연결 확인 가능)
```

### 4단계: 테스트 실행

#### 방법 A: IntelliJ IDEA에서 실행 (권장)

1. `GeminiApiEndpointTest.kt` 파일 열기
2. 각 테스트 메서드 옆의 실행 버튼 클릭
3. 또는 클래스명 옆의 실행 버튼으로 전체 테스트 실행

#### 방법 B: Gradle 명령어로 실행

```bash
# Windows
gradlew.bat test --tests GeminiApiEndpointTest

# Linux/Mac
./gradlew test --tests GeminiApiEndpointTest
```

#### 방법 C: 수동 테스트 스크립트 실행

1. `GeminiApiEndpointManualTest.kt` 파일 열기
2. `main` 함수 옆의 실행 버튼 클릭
3. 콘솔에서 상세한 결과 확인

---

## 📋 테스트 실행 체크리스트

테스트 실행 전:

- [ ] Java 환경이 설정되어 있는가?
- [ ] Gradle이 정상적으로 작동하는가?
- [ ] API Key가 올바르게 설정되었는가?
- [ ] 중간 서버가 실행 중인가?
- [ ] 네트워크 연결이 정상인가?

테스트 실행 후:

- [ ] 테스트 결과를 `WORKLOG/GEMINI_API_TEST_RESULTS.md`에 기록했는가?
- [ ] 실패한 테스트의 원인을 파악했는가?
- [ ] 문제가 있으면 이슈를 기록했는가?

---

## 🔍 문제 해결

### 문제: Java 환경이 설정되지 않음

**해결 방법**:
1. Java JDK 설치 확인
2. JAVA_HOME 환경 변수 설정
3. PATH에 Java 추가

### 문제: 서버 연결 실패

**해결 방법**:
1. 중간 서버가 실행 중인지 확인
2. 포트가 올바른지 확인 (5000)
3. 방화벽 설정 확인

### 문제: API Key 오류

**해결 방법**:
1. API Key가 올바른지 확인
2. Google AI Studio에서 새 API Key 발급
3. API Key 권한 확인

---

## 📝 테스트 결과 기록 방법

1. `WORKLOG/GEMINI_API_TEST_RESULTS.md` 파일 열기
2. 각 테스트 항목의 "실제 결과" 섹션에 결과 입력
3. 통과 여부 체크박스 선택
4. 발견된 문제가 있으면 "발견된 문제" 섹션에 기록

---

**작성일**: 2024년
**버전**: 1.0

