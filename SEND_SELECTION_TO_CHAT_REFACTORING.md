# SendSelectionToChat 기능 리팩토링 보고서

## 📋 개요

SendSelectionToChat 기능이 제대로 작동하지 않던 문제를 분석하고 리팩토링한 결과를 정리합니다.

## 🔍 발견된 문제점

### 1. **update() 메서드 비활성화**
- `update()` 메서드가 주석 처리되어 있어 액션의 활성화 상태가 제대로 관리되지 않았습니다.
- 결과적으로 텍스트가 선택되지 않았을 때도 메뉴에 표시되거나, 반대로 선택되었을 때 비활성화되는 경우가 발생했습니다.

### 2. **시각적 피드백 부족**
- 선택된 코드가 컨텍스트로 설정되었지만, 채팅창에 아무런 표시가 없어 사용자가 제대로 설정되었는지 확인할 수 없었습니다.
- `fileInfoLabel`만 업데이트되고, 실제 선택된 코드의 내용이 표시되지 않았습니다.

### 3. **에러 처리 부족**
- 예외가 발생했을 때 사용자에게 알림이 표시되지 않았습니다.
- 디버깅을 위한 로그가 부족했습니다.

### 4. **선택 영역 정보 부정확**
- 라인 범위가 단일 라인일 때와 다중 라인일 때 구분이 없었습니다.
- 파일 정보 표현이 단순했습니다.

### 5. **툴윈도우 활성화 문제**
- `toolWindow?.activate(Runnable { })`로 빈 Runnable을 전달하여 실제 활성화가 제대로 이루어지지 않을 수 있었습니다.

## ✅ 리팩토링 내용

### 1. **update() 메서드 활성화 및 개선**

```kotlin
override fun update(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR)
    val project = e.project
    
    // 에디터가 존재하고, 선택된 텍스트가 비어있지 않은 경우에만 액션을 활성화
    val hasSelection = editor != null && 
                      editor.selectionModel.hasSelection() && 
                      !editor.selectionModel.selectedText.isNullOrBlank()
    
    e.presentation.isEnabledAndVisible = project != null && hasSelection
    
    // 툴팁에 선택된 텍스트 미리보기 표시
    if (e.presentation.isEnabledAndVisible) {
        val selectedText = editor?.selectionModel?.selectedText
        val preview = selectedText?.take(50)?.replace("\n", " ")?.let { 
            if (selectedText.length > 50) "$it..." else it 
        } ?: ""
        e.presentation.text = "Send Selection to Chat${if (preview.isNotEmpty()) ": $preview" else ""}"
    }
}
```

**개선 사항:**
- ✅ 선택된 텍스트가 있을 때만 액션 활성화
- ✅ 툴팁에 선택된 텍스트 미리보기 표시
- ✅ 텍스트가 비어있거나 공백만 있는 경우 비활성화

### 2. **시각적 피드백 추가**

```kotlin
// 채팅창에 선택된 코드 미리보기 표시
val previewMessage = buildString {
    appendLine("📋 선택된 코드가 컨텍스트로 설정되었습니다.")
    appendLine("📄 파일: $fileInfo")
    appendLine("📝 선택된 코드 (${selectedText.length}자):")
    appendLine()
    appendLine("```")
    val preview = if (selectedText.length > 500) {
        selectedText.take(500) + "\n... (${selectedText.length - 500}자 더 있음)"
    } else {
        selectedText
    }
    appendLine(preview)
    appendLine("```")
    appendLine()
    appendLine("💡 이제 이 코드에 대해 질문하거나 수정 요청을 할 수 있습니다!")
}

chatService.sendMessage(previewMessage, isUser = false)
```

**개선 사항:**
- ✅ 선택된 코드가 채팅창에 미리보기로 표시됨
- ✅ 파일 정보와 코드 길이 정보 제공
- ✅ 사용자에게 다음 단계 안내

### 3. **에러 처리 및 로깅 개선**

```kotlin
try {
    // ... 기존 로직 ...
} catch (e: Exception) {
    println("[SendSelectionToChat] 오류 발생: ${e.message}")
    e.printStackTrace()
    
    // 사용자에게 오류 알림
    com.intellij.openapi.ui.Messages.showErrorDialog(
        project,
        "선택된 코드를 챗봇으로 전송하는 중 오류가 발생했습니다:\n${e.message}",
        "Send Selection to Chat 오류"
    )
}
```

**추가된 로깅:**
- ✅ 각 단계별 상세 로그 출력
- ✅ 선택된 텍스트 길이, 파일 정보 등 디버깅 정보
- ✅ 예외 발생 시 사용자에게 알림 표시

### 4. **선택 영역 정보 개선**

```kotlin
val startLine = editor.document.getLineNumber(selectionModel.selectionStart) + 1 // 1-based
val endLine = editor.document.getLineNumber(selectionModel.selectionEnd) + 1 // 1-based

val fileInfo = if (startLine == endLine) {
    "$fileName (라인: $startLine)"
} else {
    "$fileName (라인: $startLine-$endLine)"
}
```

**개선 사항:**
- ✅ 단일 라인과 다중 라인 구분
- ✅ 1-based 라인 번호 사용 (사용자 친화적)
- ✅ 더 정확한 파일 정보 표시

### 5. **setSelectionContext() 메서드 개선**

```kotlin
fun setSelectionContext(code: String, fileInfo: String) {
    try {
        println("[ChatService] setSelectionContext 호출: 파일=$fileInfo, 코드 길이=${code.length}자")
        
        // 입력 검증
        if (code.isBlank()) {
            println("[ChatService] 경고: 선택된 코드가 비어있습니다.")
            return
        }
        
        selectedCode = code
        selectedFileInfo = fileInfo
        
        // 현재 선택 영역의 오프셋 정보도 저장
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor != null) {
            val selectionModel = editor.selectionModel
            if (selectionModel.hasSelection()) {
                selectedStartOffset = selectionModel.selectionStart
                selectedEndOffset = selectionModel.selectionEnd
                selectedDocument = editor.document
                println("[ChatService] 선택 오프셋 저장: $selectedStartOffset-$selectedEndOffset")
            } else {
                println("[ChatService] 현재 에디터에 선택 영역이 없지만 코드는 저장했습니다.")
            }
        }
        
        ApplicationManager.getApplication().invokeLater {
            fileInfoLabel?.text = "선택된 파일: $fileInfo"
            fileInfoLabel?.isVisible = true
            fileInfoLabel?.toolTipText = "선택된 코드: ${code.take(100)}..."
        }
        
        println("[ChatService] 선택 컨텍스트 설정 완료")
        
    } catch (e: Exception) {
        println("[ChatService] setSelectionContext 오류: ${e.message}")
        e.printStackTrace()
    }
}
```

**개선 사항:**
- ✅ 입력 검증 추가
- ✅ 상세한 로깅
- ✅ 툴팁에 선택된 코드 미리보기 추가
- ✅ 예외 처리 강화

### 6. **툴윈도우 활성화 개선**

```kotlin
// 툴윈도우 활성화
val toolWindowManager = ToolWindowManager.getInstance(project)
val toolWindow = toolWindowManager.getToolWindow("Protein26")
toolWindow?.activate(null) // null 전달로 기본 동작 사용
```

**개선 사항:**
- ✅ `null` 전달로 기본 활성화 동작 사용
- ✅ 더 안정적인 툴윈도우 활성화

## 🧪 테스트 시나리오

### 시나리오 1: 정상적인 코드 선택 및 전송
1. **준비**: 에디터에서 코드 일부 선택
2. **실행**: 우클릭 → "Send Selection to Chat" 선택
3. **기대 결과**:
   - ✅ 채팅창에 선택된 코드 미리보기 표시
   - ✅ 파일 정보 레이블 업데이트
   - ✅ 툴윈도우 활성화
   - ✅ 콘솔에 로그 출력

### 시나리오 2: 선택된 텍스트가 없을 때
1. **준비**: 에디터에서 아무것도 선택하지 않음
2. **실행**: 우클릭 메뉴 확인
3. **기대 결과**:
   - ✅ "Send Selection to Chat" 메뉴 항목이 비활성화되어 있거나 표시되지 않음

### 시나리오 3: 공백만 선택된 경우
1. **준비**: 에디터에서 공백 문자만 선택
2. **실행**: 우클릭 → "Send Selection to Chat" 선택
3. **기대 결과**:
   - ✅ 메뉴 항목이 비활성화됨
   - ✅ 또는 실행되지 않음

### 시나리오 4: 다중 라인 선택
1. **준비**: 에디터에서 여러 라인 선택 (예: 함수 전체)
2. **실행**: 우클릭 → "Send Selection to Chat" 선택
3. **기대 결과**:
   - ✅ 파일 정보에 라인 범위 표시 (예: "파일명.kt (라인: 10-25)")
   - ✅ 선택된 전체 코드가 미리보기로 표시 (500자 초과 시 잘림)
   - ✅ 코드 길이 정보 표시

### 시나리오 5: 오류 발생 시
1. **준비**: 프로젝트가 없거나 에디터가 없는 상태
2. **실행**: 액션 실행 시도
3. **기대 결과**:
   - ✅ 예외가 발생하지 않고 안전하게 종료
   - ✅ 또는 오류 다이얼로그 표시
   - ✅ 콘솔에 오류 로그 출력

## 📊 테스트 결과

### ✅ 성공한 항목
- ✅ 액션 활성화/비활성화 정상 작동
- ✅ 선택된 코드 채팅창 표시
- ✅ 파일 정보 레이블 업데이트
- ✅ 라인 번호 정확히 계산
- ✅ 툴팁에 선택된 텍스트 미리보기
- ✅ 로그 출력 정상
- ✅ 예외 처리 정상

### ⚠️ 주의사항
- 선택된 코드가 매우 길 경우 (500자 이상) 미리보기는 처음 500자만 표시됩니다.
- 툴윈도우가 이미 열려있지 않은 경우, `activate()` 호출이 약간의 지연이 있을 수 있습니다.

## 🔧 추가 개선 사항

### 향후 개선 가능한 사항
1. **코드 미리보기 크기 조정**: 사용자 설정에서 미리보기 길이 조정 가능
2. **선택 영역 하이라이팅**: 에디터에서 선택된 영역을 시각적으로 강조
3. **키보드 단축키**: 기본 단축키 추가 (현재는 메뉴에서만 접근 가능)
4. **선택 이력 관리**: 최근 선택한 코드 목록 유지

## 📝 결론

SendSelectionToChat 기능이 이제 정상적으로 작동합니다. 주요 개선 사항:

1. ✅ **사용자 경험 개선**: 선택된 코드가 채팅창에 표시되어 시각적 피드백 제공
2. ✅ **안정성 향상**: 에러 처리 및 입력 검증 추가
3. ✅ **디버깅 용이성**: 상세한 로그 출력
4. ✅ **정확성 향상**: 라인 번호 계산 및 파일 정보 표시 개선

모든 테스트 시나리오를 통과했으며, 사용자가 선택한 코드를 챗봇으로 전송하는 기능이 안정적으로 작동합니다.

