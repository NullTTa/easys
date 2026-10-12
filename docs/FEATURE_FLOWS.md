# 기능 흐름

코드에서 확인한 흐름만 그렸습니다. 각 흐름 끝의 **검증** 줄은 2026-10-12 실제 구동 결과입니다.

## 1. AI 챗봇

```mermaid
flowchart TD
    Q["사용자 질문<br/>POST /api/ai/chat"] --> G{"비로그인 무료 횟수<br/>남았나?"}
    G -- "아니오" --> SU["회원가입 유도 + SIGNUP 버튼"]
    G -- "예" --> C["FeatureIntentClassifier<br/>의도 분류"]
    C -->|"탐색형 질문<br/>(뭘 배워야 할지 모르겠어)"| F1["고정 안내 응답<br/>(LLM 호출 없음)"]
    C -->|"기능 + 이동 요청<br/>(멘토링 페이지로 이동시켜줘)"| F2["안내 문구 + NAVIGATION 액션"]
    C -->|"내 일정 / 인기 방송 등"| F3["DB 조회 결과로 응답<br/>(LLM 호출 없음)"]
    C -->|"서비스 소개 / 기능 설명"| L1["시스템 프롬프트 + 서비스 지식<br/>+ Redis 최근 대화"]
    C -->|"일반·학습 질문"| L2["시스템 프롬프트<br/>(학습 문제 출제는 대화 기록 제외)"]
    L1 --> O["Ollama /api/chat<br/>qwen2.5:3b"]
    L2 --> O
    O --> P["후처리<br/>(한자·가나 섞임 필터 등)"]
    P --> R["응답 + actions"]
    F1 --> R
    F2 --> R
    F3 --> R
    R --> S["Redis에 대화 저장<br/>(최대 턴 수·TTL)"]
```

- 오타("멪토링")는 자모 단위 편집거리로 찾고, `confirmTypo=true`로 사용자에게 확인을 받습니다.
- 페이지 이동 버튼은 `ActionDto { type: NAVIGATION, target: MENTORING }` 형태입니다. 실제 경로는 프론트엔드가 `target`으로 정합니다.

**검증:** 서비스 소개, 이동 버튼 클릭 → `/mentor` 이동, 오타 확인, 탐색형 고정 안내, 내 일정 조회, 비로그인 3회 제한을 확인했습니다.

## 2. 멘토링: 신청 → 승인 → 결제 → 일정 저장

```mermaid
sequenceDiagram
    actor Mentee as 신청자
    actor Mentor as 멘토
    participant BE as Spring Boot
    participant Toss as TossPayments
    Mentor->>BE: POST /mentor (멘토 프로필), POST /mentor/offerings (날짜별 시간대)
    Mentee->>BE: POST /mentor/reservation/{mentorId}
    BE-->>Mentee: 예약 PENDING + 결제 정보 READY 생성
    Mentor->>BE: PUT /mentor/reservation/{id}/approve
    BE-->>Mentee: 상태 APPROVED + 알림 "결제를 진행해주세요"
    Mentee->>Toss: 결제창 (테스트)
    Toss-->>Mentee: paymentKey, orderId, amount
    Mentee->>BE: POST /payments/confirm
    BE->>BE: 주문 소유자 확인, 금액이 DB 값과 같은지 확인
    BE->>Toss: POST /v1/payments/confirm (시크릿 키는 서버에만)
    Toss-->>BE: 승인 결과
    BE->>BE: onMentoringPaymentConfirmed() → 멘토·신청자 캘린더에 일정 생성
    BE-->>Mentor: 결제 완료 알림
    BE-->>Mentee: 결제 완료 알림
    Mentee->>BE: PUT /mentor/reservation/{id}/complete (결제 PAID인 예약만)
    Mentee->>BE: POST /mentor/reviews (후기)
```

- **거절** (`PUT .../reject`): 결제를 취소하고, 이미 만든 일정이 있으면 지웁니다.
- 승인(`APPROVED`) 전에는 결제를 확정할 수 없습니다.
- 완료 처리는 결제가 끝난 예약에 대해 신청자가 합니다. 코드 주석상 시연을 위해 상담 날짜를 기다리지 않도록 한 설계입니다.

**검증:** 멘토 프로필·멘토링 등록 → 신청 → 승인 → 결제 대기(`READY`) 화면까지 확인했습니다. 금액 변조(1원)와 다른 사람 주문 승인 시도가 Toss 호출 전에 거부되는 것도 확인했습니다. Toss 실제 승인, 그 뒤 일정 생성, 완료·후기는 **미검증**입니다.

## 3. 스터디카페 예약 ↔ 캘린더

```mermaid
stateDiagram-v2
    [*] --> PENDING: 예약 생성
    PENDING --> PAID: Toss 결제 승인 (onStudyPaymentConfirmed)
    PAID --> CONFIRMED: 관리자 승인 (approveReservation)
    CONFIRMED --> CANCELLED: 관리자 취소 → 일정 삭제
    PAID --> CANCELLED: 관리자 취소
    PENDING --> CANCELLED: 사용자 취소
    note right of CONFIRMED
        개인 예약 → 개인 캘린더 일정
        스터디 예약 → 스터디 그룹 일정
        + 참여자 알림·이메일
    end note
```

- 일정 생성은 `ReservationService.confirmReservationAndCreateCalendar()`에서 이루어집니다.
- 결제 승인 전에 결제 기한(`assertPaymentDeadlineNotPassed`)을 확인합니다.

**검증:** 스터디룸 목록 화면만 확인했습니다. 예약 → 결제 → 승인 → 일정 생성은 **미검증**입니다.

## 4. 스트리밍 미니 캘린더

```mermaid
sequenceDiagram
    actor Host as 방장
    actor Viewer as 시청자
    participant BE as Spring Boot
    participant WS as WebSocket /signal
    Host->>BE: POST /api/calendar/personal (streamingRoomId = 방 ID)
    Host->>WS: { type: "schedule-updated" }
    WS-->>Viewer: 같은 방 사용자에게 전달
    Viewer->>BE: GET /api/calendar/personal/room/{roomId}
    Viewer->>BE: POST /api/calendar/personal ("[방송]" 사본, streamingRoomId 없음)
    Viewer->>BE: DELETE /api/calendar/personal/{copyId} (사본만 취소)
```

**검증:** 방장 화면에서 방 일정 등록 후 해당 날짜 표시(REC)를 확인했습니다. 시청자 화면과 실시간 갱신은 **미검증**입니다.

## 5. 멘토링 실시간 채팅과 알림

```mermaid
sequenceDiagram
    actor A as 멘토 또는 신청자
    actor B as 상대방
    participant BE as Spring Boot
    participant Broker as STOMP Simple Broker
    B->>Broker: SUBSCRIBE /topic/mentoring/{reservationId}
    Broker->>BE: 구독 가로채기 → 예약 참여자인지 확인 (assertParticipant)
    A->>BE: POST /mentor/reservation/{id}/chat/messages
    BE->>BE: 메시지 저장
    BE->>Broker: convertAndSend /topic/mentoring/{id}
    Broker-->>B: 새 메시지
    BE-->>B: 알림 MENTORING_CHAT_MESSAGE
```

- 멘토링 알림 종류: `MENTORING_RESERVATION_APPROVED`, `MENTORING_PAYMENT_COMPLETED`, `MENTORING_CHAT_MESSAGE`
- 알림 저장·조회 API와 헤더 알림 벨은 팀원이 만들었고, 멘토링 이벤트에서 알림을 보내는 부분을 본인이 구현했습니다.

**검증:** 코드 경로만 확인했습니다. 실제 송수신과 알림 표시는 **미검증**입니다.
