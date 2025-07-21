# 소진공 AI 챗봇 플러그인

IntelliJ IDE에 내장된 AI 챗봇을 통해 개발 생산성을 향상시키세요.

## 주요 기능

*   **지능형 코드 분석 및 응답:** 코드에 대해 궁금한 점을 질문하거나 일반적인 대화를 나눌 수 있습니다.
*   **코드 수정 및 리팩토링:** "이 함수에 주석 추가해줘" 또는 "이 코드 개선해줘" 와 같은 자연어 명령으로 코드를 쉽게 수정할 수 있습니다.
*   **순차적 변경사항 적용:** 여러 군데를 수정해야 하는 경우, 각 변경 사항을 하나씩 미리보고 적용하거나 거절할 수 있어 안전하고 정교한 코드 관리가 가능합니다.
*   **로컬 LLM 연동:** LM Studio와 같은 로컬 LLM 서버와 연동하여 동작하므로, 코드가 외부로 유출되지 않습니다.

## 개발 환경 설정

이 프로젝트는 IntelliJ Platform Plugin 개발을 위한 표준 환경을 사용합니다.

### 필수 도구

*   **IntelliJ IDEA Ultimate (권장):** 플러그인 개발에 최적화된 IDE입니다. Community Edition도 가능하지만 일부 기능 제약이 있을 수 있습니다.
*   **Java Development Kit (JDK) 17 이상:** 프로젝트 빌드 및 실행에 필요합니다.
*   **Gradle:** 의존성 관리 및 빌드 자동화 도구입니다. 프로젝트에 포함된 `gradlew` (Gradle Wrapper)를 사용하므로 별도 설치는 필요하지 않습니다.

### 프로젝트 열기

1.  IntelliJ IDEA를 엽니다.
2.  `File` -> `Open`을 선택하고, 이 프로젝트의 루트 디렉토리(`semasChatbot`)를 선택합니다.
3.  Gradle 프로젝트로 인식되면, 필요한 의존성을 자동으로 다운로드하고 프로젝트를 동기화합니다.

## 언어

이 프로젝트는 **Kotlin** 언어로 작성되었습니다. Kotlin은 JetBrains에서 개발한 정적 타입 프로그래밍 언어로, JVM에서 실행되며 Java와의 100% 상호 운용성을 제공합니다.

## 프로젝트 구조

```
semasChatbot/
├── .gradle/                  # Gradle 관련 파일
├── .idea/                    # IntelliJ IDEA 프로젝트 설정 파일
├── build/                    # 빌드 결과물 (플러그인 JAR 등)
├── gradle/                   # Gradle Wrapper 파일
├── src/
│   ├── main/
│   │   ├── kotlin/           # Kotlin 소스 코드
│   │   │   └── org/dev/semaschatbot/
│   │   │       ├── ChatService.kt              # 챗봇 핵심 로직 및 LLM 연동
│   │   │       ├── CustomButtonRenderer.kt     # 코드 변경 제안 UI 렌더링
│   │   │       ├── LLMChatToolWindowFactory.kt # 챗봇 툴 윈도우 UI 정의
│   │   │       ├── LmStudioClient.kt           # LM Studio API 클라이언트
│   │   │       └── SendSelectionToChatAction.kt# 코드 선택 후 챗봇으로 전송 액션
│   │   └── resources/        # 리소스 파일 (plugin.xml, 메시지 번들 등)
│   │       └── META-INF/
│   │           ├── MessagesBundle.properties   # UI 메시지 번들
│   │           ├── plugin.xml                  # 플러그인 메타데이터 및 확장 포인트 정의
│   │           └── pluginIcon.svg              # 플러그인 아이콘
├── build.gradle.kts          # Gradle 빌드 스크립트 (Kotlin DSL)
├── settings.gradle.kts       # Gradle 설정 파일
└── README.md                 # 프로젝트 설명 문서
```

## 시작하기

### 1. LM Studio 설정

이 플러그인은 로컬에서 실행되는 LLM(Large Language Model) 서버와 연동됩니다. LM Studio를 사용하여 로컬 LLM 환경을 구축하는 방법을 안내합니다.

*   **LM Studio 다운로드 및 설치:**
    [LM Studio 공식 웹사이트](https://lmstudio.ai/)에서 운영체제에 맞는 버전을 다운로드하여 설치합니다.

*   **LLM 모델 다운로드:**
    LM Studio 애플리케이션 내에서 원하는 LLM 모델을 검색하고 다운로드합니다. (예: `llama-2`, `mistral` 등)

*   **로컬 서버 시작:**
    LM Studio에서 다운로드한 모델을 로드한 후, "Local Inference Server" 탭으로 이동하여 서버를 시작합니다. 기본 포트는 `1234`입니다.

*   **플러그인 LM Studio 연동 설정:**
    `src/main/kotlin/org/dev/semaschatbot/LmStudioClient.kt` 파일에서 `baseUrl` 변수를 로컬 LM Studio 서버의 주소로 변경합니다.
    ```kotlin
    class LmStudioClient(
        private val baseUrl: String = "http://192.168.18.52:1234/v1" // LM Studio 서버 주소에 맞게 변경
    ) {
        // ...
    }
    ```
    **참고:** `192.168.18.52`는 예시 IP 주소입니다. LM Studio 서버가 실행되는 실제 IP 주소 또는 `localhost`를 사용하세요.

### 2. 플러그인 빌드 및 실행

IntelliJ IDEA 내에서 플러그인을 빌드하고 테스트용 IDE 인스턴스에서 실행할 수 있습니다.

*   **플러그인 빌드:**
    터미널에서 다음 Gradle 명령어를 실행하여 플러그인 JAR 파일을 빌드합니다.
    ```bash
    ./gradlew buildPlugin
    ```
    빌드된 플러그인 파일은 `build/distributions/` 디렉토리에 생성됩니다.

*   **IDE에서 실행 (개발용):**
    IntelliJ IDEA의 Gradle 탭에서 `Tasks` -> `intellij` -> `runIde` 태스크를 실행합니다.
    또는 `Run/Debug Configurations`에서 "Run IDE with Plugin" 설정을 사용하여 실행할 수 있습니다.
    이것은 플러그인이 설치된 새로운 IntelliJ IDEA 인스턴스를 시작합니다.

### 3. 챗봇 사용

새로운 IDE 인스턴스가 시작되면:

*   IDE 우측에 "소진공챗봇(개발자전용)" 툴 윈도우가 나타납니다. 클릭하여 엽니다.
*   챗봇 입력 필드에 질문을 입력하거나, 코드 에디터에서 코드를 선택한 후 마우스 오른쪽 버튼 클릭 -> "Send Selection to Chatbot"을 선택하여 챗봇에게 코드를 전송할 수 있습니다.

## 빌드

Gradle을 사용하여 프로젝트를 빌드할 수 있습니다.

```bash
./gradlew buildPlugin
```

## 기여

이 프로젝트는 오픈 소스이며, 기여를 환영합니다. 버그 리포트, 기능 제안, 풀 리퀘스트 등 다양한 방법으로 참여할 수 있습니다.



`챗봇 플러그인 개발 (전체 프로젝트)
├── 1. 준비 단계 (Preparation Phase) – 예상 1-2일
│   ├── 1.1 요구사항 분석
│   │   ├── 기능 정의: 챗봇이 제공할 기능 목록 작성 (e.g., 코드 생성, 오류 진단, 자연어 처리).
│   │   ├── 사용자 시나리오 작성: IntelliJ 내에서 챗봇을 어떻게 호출하고 상호작용할지 스토리보드.
│   │   ├── 기술 스택 결정: Kotlin, JetBrains SDK, 외부 API (e.g., OpenAI API for 챗봇 로직) 통합 여부.
│   │   └── 예상 시간: 4-6시간. 도구: Google Docs 또는 Notion으로 문서화.
│   └── 1.2 개발 환경 설정
│       ├── IntelliJ 설치 및 Kotlin 플러그인 활성화.
│       ├── Plugin SDK 다운로드 및 Gradle 빌드 설정 (build.gradle.kts 작성).
│       ├── Git 저장소 초기화 및 기본 프로젝트 구조 생성 (e.g., src/main/kotlin 디렉토리).
│       └── 예상 시간: 2-4시간. 지식: Gradle Kotlin DSL 기본 이해.
├── 2. 설계 단계 (Design Phase) – 예상 2-3일
│   ├── 2.1 플러그인 아키텍처 설계
│   │   ├── 컴포넌트 분해: Action, Tool Window, Service 등 IntelliJ API 활용.
│   │   ├── 챗봇 로직 설계: 입력 처리 → NLP 처리 → 응답 생성 흐름 (e.g., Kotlin Coroutines for 비동기).
│   │   └── UML 다이어그램 작성: 클래스 다이어그램 (PlantUML 또는 draw.io 사용).
│   │       └── 예상 시간: 6-8시간. 도구: draw.io 또는 IntelliJ 내 UML 플러그인.
│   └── 2.2 UI/UX 설계
│       ├── 챗봇 인터페이스 디자인: Tool Window에 채팅 UI (Swing 또는 Compose for Desktop 사용).
│       ├── 사용자 입력/출력 흐름 정의: 키보드 단축키 (e.g., Ctrl+Shift+C)로 호출.
│       └── 접근성 고려: 다크/라이트 테마 지원.
│           └── 예상 시간: 4-6시간. 지식: IntelliJ UI Guidelines 준수.
├── 3. 구현 단계 (Implementation Phase) – 예상 5-7일 (가장 핵심)
│   ├── 3.1 기본 플러그인 구조 구현
│   │   ├── plugin.xml 작성: 플러그인 메타데이터 (name, description, actions).
│   │   ├── Action 클래스 생성: Kotlin으로 AnAction 상속하여 챗봇 호출 로직.
│   │   └── Service 클래스 구현: 싱글톤 서비스로 챗봇 상태 관리.
│   │       └── 예상 시간: 8-10시간. 지식: IntelliJ Platform API 문서 참조.
│   ├── 3.2 챗봇 기능 코딩
│   │   ├── 입력 처리: Editor에서 선택된 코드 읽기 (PsiElement 사용).
│   │   ├── NLP/로직 구현: Kotlin으로 간단한 룰 기반 또는 외부 API 호출 (e.g., HttpClient for OpenAI).
│   │   ├── 응답 출력: Tool Window에 텍스트 렌더링, 코드 삽입 기능 추가.
│   │   └── 에러 핸들링: 예외 처리 (e.g., 네트워크 오류 시 fallback 메시지).
│   │       └── 예상 시간: 10-15시간. 도구: Kotlin Coroutines, Ktor Client (Gradle dependency 추가).
│   └── 3.3 통합 및 리팩토링
│       ├── 모듈 통합: Action → Service → UI 연결.
│       ├── 코드 리뷰: Kotlin 스타일 가이드 준수 (e.g., null safety 활용).
│       └── 성능 최적화: 메모리 누수 방지 (WeakReference 사용).
│           └── 예상 시간: 4-6시간.
├── 4. 테스트 단계 (Testing Phase) – 예상 2-3일
│   ├── 4.1 단위 테스트
│   │   ├── JUnit/Kotest로 챗봇 로직 테스트 (e.g., 입력-출력 mock).
│   │   └── IntelliJ API 모킹: PluginTestCase 상속.
│   │       └── 예상 시간: 6-8시간. 도구: Kotest 또는 JUnit5.
│   ├── 4.2 통합 테스트
│   │   ├── 실제 IntelliJ에서 플러그인 로드 및 챗봇 동작 확인.
│   │   └── 에지 케이스 테스트: 대량 입력, 오류 시나리오.
│   │       └── 예상 시간: 4-6시간.
│   └── 4.3 사용자 테스트
│       ├── 베타 테스터 피드백 수집 (e.g., GitHub Issue).
│       └── 버그 수정 루프.
│           └── 예상 시간: 2-4시간.
└── 5. 배포 및 유지보수 단계 (Deployment & Maintenance Phase) – 예상 1-2일
├── 5.1 배포 준비
│   ├── 플러그인 빌드: Gradle task로 ZIP 파일 생성.
│   ├── JetBrains Marketplace 업로드: plugin.xml 버전 관리.
│   └── 문서 작성: README.md에 설치/사용법 설명.
│       └── 예상 시간: 4-6시간. 지식: JetBrains Plugin Repository 가이드.
└── 5.2 유지보수 계획
├── 버전 업데이트: Kotlin/IntelliJ 버전 호환성 체크.
├── 이슈 트래킹: GitHub로 사용자 피드백 관리.
└── 보안 검토: API 키 노출 방지.
└── 예상 시간: 지속적 (초기 2-4시간).`