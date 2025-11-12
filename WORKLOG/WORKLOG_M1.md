# M1 작업일지

## 개요
- 목표: 스트리밍 응답, 대화/세션 기초, 설정 화면, 다중 프로바이더 추상화, RAG 임베딩 기본형 중 1단계(스트리밍 응답) 구현

## 변경 사항 요약
- LmStudioClient
  - sendChatRequestStream 추가: SSE 기반 델타 수신(onDelta), 완료(onComplete), 오류(onError) 콜백 지원
  - 스트리밍 실패 시 전체 본문 파싱 폴백 처리
- ChatService
  - 기존 SwingWorker 동기 호출 제거, 스트리밍 콜백으로 UI 점진 업데이트
  - 빈 AI 버블 생성 후 델타를 누적하여 표시, 스크롤 하단 고정 유틸 추가

## 주요 코드
```
LmStudioClient.sendChatRequestStream(userMessage, systemMessage, onDelta=..., onComplete=..., onError=...)
```

```
ChatService.sendChatRequestToLLM()
- 초기 빈 메시지 패널 생성
- onDelta마다 JTextArea에 텍스트 누적
- 완료/오류 시 로딩 해제 및 컨텍스트 정리
```

## 영향
- 사용자 경험: 응답을 실시간으로 확인 가능, 긴 응답 대기감 감소
- 구조: 향후 레시피/명령에서도 동일 스트리밍 경로 활용 가능

## 후속 작업(다음 단계 제안)
- SSE 이외 스트리밍 포맷 감지 보강 및 테스트
- 코딩 보조 응답 시 코드블록 Markdown 렌더링 강화
- 취소(Stop) 버튼 추가 및 요청 취소 처리
- 세션/히스토리 저장 및 요약 도입


