# 캠퍼스 스터디룸 예약·배정 시스템 (배정 정책 교체형)

소프트웨어아키텍처 팀 프로젝트의 Java 구현이다. ADD로 설계한 Boundary–Control–Device 3계층과
Control의 세 구역(성능·변경 용이성·가용성)을 그대로 코드로 옮겼다.

| 모듈 | 내용 | 상태 |
| --- | --- | --- |
| `core` | 순수 Java 17. 아키텍처 전체와 메모리 Device 구현, 테스트 96개, QAS 데모 | 빌드·테스트 통과 확인 |
| `app` | Spring Boot 3. REST API와 AWS Adapter(SQS·Redis·RDS·EventBridge Scheduler) | 기본 프로필 기동과 예약 접수(202)는 확인함(2026-11-16). **그 뒤 추가한 조회 API(`QueryController`)와 aws 프로필은 Spring으로 빌드·실행해 본 적 없음** |
| `frontend` | React + TypeScript + Tailwind. 학생 예약·관리자 정책 콘솔·QR 키오스크 화면 | 타입 검사·빌드·단위 테스트 77개 통과. 서버 없이 여는 데모 빌드(`npm run build:demo`)도 있다. 코어 로직을 띄운 검증용 서버에 붙여 전체 시나리오 확인. Spring 서버(`bootRun`)에 붙여 본 적은 없음 |

## 업무 규칙 (11/19 회의)

- 신청자 포함 **2명 이상**이어야 신청할 수 있다. `POST /reservations` 본문에 함께 이용하는 사람의 학번을 넣는다.
  `bidAmount`는 **1인당** 입찰액이고 구성원 전원이 같은 금액을 건다.
  ```json
  {"roomId":"ENG-KC-1","startsAt":"2026-12-01T05:00:00Z","bidAmount":30,
   "companions":[{"studentId":"202415176"}]}
  ```
- 동행 학번은 **실제 재학생**이어야 한다. 창구가 학사 SSO(`IAuthPort.lookupStudent`)로 확인하고, 아니면 400 `NOT_ENROLLED:학번`.
  학사 시스템에 닿지 못하면 503. 동행의 **동의 절차는 없다**(신청자 책임)
- 등록된 학번(신청자·동행 모두)은 **같은 시간에 다른 진행 중 예약**(접수 중·확정·입실)에 들어갈 수 없다. 거절 사유 `MEMBER_TIME_CONFLICT`
- 순위는 **팀 합계**(1인당 입찰액 × 인원)로 정한다.
  합계가 같으면 **팀 패널티**(구성원의 이번 학기 노쇼 횟수 합)가 적은 팀, 그래도 같으면 **인원이 많은 팀**, 그래도 같으면 먼저 접수된 팀
- 크레딧 베팅은 **전원이 같은 금액**을 건다(한 명만 많이 걸 수 없다). 각자 1인당 금액만 묶이고, 한 명이라도 모자라면 아무도
  묶지 않고 `INSUFFICIENT_CREDIT`. 낙찰가도 전원이 똑같이 낸다(1등가면 각자 1인당 금액 그대로)
- 결과 알림(확정·거절·취소·노쇼 해제)은 **등록된 구성원 전원**에게 간다. 동행에게는 "OOO님이 함께 신청한 …"으로 보낸다
- 하루 한도(선착순 1시간, 크레딧 베팅 2시간)는 구성원 **각자**에게 적용된다(동행으로 들어간 시간도 센다). 크레딧은 시험 기간마다 100으로 초기화, 이월 없음
- 같은 시간 중복은 지금 **락 + 조회**로 막는다. DB 유니크 제약 방식과의 비교는 성능 측정 항목으로 남겨 두었다
- 최소 인원은 설정값(`studyroom.min-party-size`, 기본 2). 테스트 중 인원과 무관한 것은 1로 두고 돌린다
- 시험용 SSO(`StubSsoAuthAdapter`)는 `000`으로 시작하는 학번을 재학생이 아닌 것으로 본다

## 빠른 시작

JDK 17 이상이 필요하다.

```bash
./gradlew :core:test          # 테스트 96개
./gradlew :core:run           # QAS 시나리오 4개를 돌려 결과 출력
./gradlew :app:bootRun        # REST 서버 (기본 프로필 = 메모리 Device, AWS 불필요)
```

네트워크 없이 코어만 볼 때는 `./gradlew -PcoreOnly :core:build`를 쓴다(app 모듈을 빼고 구성한다).

서버를 띄운 뒤 호출 예시:

```bash
# 예약 접수 → 202 + 티켓
curl -i -X POST localhost:8080/reservations \
  -H 'Authorization: Bearer user:202412345' -H 'Idempotency-Key: 7f3a-1' \
  -H 'Content-Type: application/json' \
  -d '{"roomId":"A101","startsAt":"2026-12-01T09:00:00Z"}'

# 결과 폴링
curl localhost:8080/tickets/{ticketId} -H 'Authorization: Bearer user:202412345'

# 정책 교체 (FCFS | CREDIT_BETTING) → 202 + switchId, 진행 중이면 409
curl -i -X POST localhost:8080/admin/policy/switch \
  -H 'Authorization: Bearer admin:root' -H 'Content-Type: application/json' \
  -d '{"target":"CREDIT_BETTING"}'

# QR 체크인 (기기 웹훅). roomId는 기기가 설치된 호실(생략하면 호실 확인 안 함). 시작 10분 전 ~ 시작 10분 뒤에만 된다
curl -X POST localhost:8080/devices/qr/scan -H 'Content-Type: application/json' -d '{"payload":"RSV:{예약 id}","roomId":"A101"}'

# 조회 (화면이 읽는다): 현재 정책, 차 있는 슬롯, 내 크레딧, 예약 상세, 대기열 길이
curl localhost:8080/policy -H 'Authorization: Bearer user:202412345'
curl 'localhost:8080/slots?roomId=A101,A102&from=2026-12-01T00:00:00Z&to=2026-12-02T00:00:00Z' -H 'Authorization: Bearer user:202412345'
curl localhost:8080/credits/me -H 'Authorization: Bearer user:202412345'
curl localhost:8080/reservations/{예약 id} -H 'Authorization: Bearer user:202412345'
curl localhost:8080/queue -H 'Authorization: Bearer user:202412345'
```

화면은 `frontend/`에 있다. 서버를 띄운 채 `cd frontend && npm install && npm run dev` 후 http://localhost:5173 을 연다
(`frontend/README.md` 참고).

토큰은 SSO 가짜 구현의 형식이다: `user:{학번}`, `user:{학번}:{우선순위}`, `admin:{id}`.

## 구조

```
core/src/main/java/com/studyroom
├── boundary/                      창구: StudentReservationApi, AdminPolicyApi, QueryApi(조회 전용), RateLimiter
├── control/
│   ├── performance/               [성능 · 이건희] ReservationIntake, AllocationProcessor
│   │                              (+ PartyGuard, SlotDecider, CloseTimeAllocator)
│   │   └── api/                   IReservationIntake, IAdmissionQueue, ISlotLock, ISlotStatus, IAllocationGate
│   ├── modifiable/                [변경 용이성 · 곽재민] 정책 2종, PolicyRegistry, PolicySwitchManager, CreditLedger
│   │   └── api/                   IAllocationPolicy, IPolicyProvider, IPolicySwitch, ICreditLedger, ICreditRepository,
│   │                              IAuthPort, INotifier, ICheckInDevice
│   └── availability/              [가용성 · 안서준] ReservationLifecycle, ReservationState, CheckInService,
│       │                          NoShowTimeoutHandler, OutboxRelay, ReservationQueryService(조회 전용)
│       └── api/                   IReservationLifecycle, IReservationQuery, ICheckIn, INoShowListener,
│                                  IReservationRepository, IDelayedMessage
├── device/
│   ├── performance/               메모리 큐(SQS FIFO 흉내), 슬롯 락, 슬롯 캐시
│   ├── modifiable/                SSO·알림·QR Adapter, 전환 상태 저장소
│   └── availability/              메모리 저장소(RDS 흉내), 지연 메시지(EventBridge Scheduler 흉내)
├── domain/                        Reservation, SlotId, Ticket 등 공용 값
├── demo/QasDemo.java              QAS 시나리오 실행기
└── StudyRoomSystem.java           조립 루트 (메모리 Device로 시스템 전체를 띄운다)
```

계층·구역 규칙은 `ArchitectureRulesTest`가 소스의 import로 검사한다.

- 호출은 위에서 아래로만 한다. Control은 Boundary·Device를 import하지 않는다.
- 다른 구역은 인터페이스(`api` 패키지)로만 참조한다. 구현 클래스는 import하지 않는다.
- `api` 패키지는 `domain`과 자기 자신만 본다. 그래서 패키지 순환이 없다.
- 아래에서 위로는 이벤트로만 알린다(타이머 만료, QR 태그).

## 패턴과 전술이 있는 곳

| 설계 결정 | 코드 |
| --- | --- |
| Strategy (배정 정책) | `IAllocationPolicy` ← `FcfsPolicy`, `CreditBettingPolicy` |
| Registry + Feature Toggle | `PolicyRegistry`, `PolicySwitchManager` (ACTIVE → SWITCH_REQUESTED → DRAINING → ACTIVE) |
| State (예약 라이프사이클) | `ReservationState` (Requested, Confirmed, CheckedIn, Terminal) + `ReservationLifecycle` |
| Adapter (외부 3종) | `IAuthPort`, `INotifier`, `ICheckInDevice` ← `device/modifiable/*Adapter` |
| 비동기 접수 (202 + 티켓) | `ReservationIntake.submit` |
| 큐 + 분산 락 + 유니크 제약 | `IAdmissionQueue` → `ISlotLock` → `IReservationRepository` |
| 예약별 1회성 지연 메시지 | `IDelayedMessage`, `NoShowTimeoutHandler` |
| 멱등 수신 (조건부 UPDATE) | `IReservationRepository.updateStatusIf(id, from, to, …)` |
| Outbox | `OutboxEntry`, `OutboxPublisher`, `OutboxRelay` |
| Graceful degradation (Redis 장애) | `CacheUnavailableException`을 잡는 `ReservationIntake`, `AllocationProcessor` |

## QAS 확인 결과

`./gradlew :core:run`의 출력이다(2026-11-16, 메모리 구현). 지연 시간은 AWS에서의 수치가 아니다.
여기서 확인되는 것은 정합성 기준이고, 접수 p95 300ms·결과 p95 3초는 AWS에 올려 k6로 재야 한다.

| QAS | 시나리오 | 결과 |
| --- | --- | --- |
| 1 성능 | 1,000건을 슬롯 40개에 동시 요청 | 확정 40, 거절 960, 합계 1,000(유실 0), 이중 예약 0, 5xx 0 |
| 2 변경 용이성 | 초당 약 50건 처리 중 선착순 → 크레딧 베팅 | 전환 5ms, 접수 거절 0, 유실 0, 두 정책이 섞인 배정 0 |
| 3 가용성 | 예약 40건 중 미입실 30건의 유예 만료 + 중복 메시지 40건 | 노쇼 해제 30, 조건부 UPDATE 30회(건당 1회), 중복·지각 메시지 40건 무시 |
| 4 변경 용이성 | 알림 Adapter를 다른 구현으로 교체 | Control 수정 없이 동작 (`Qas4AdapterSwapTest`) |
| 5 가용성 | 캐시가 죽은 채 1,000건 | 5xx 0, 확정 40, 거절 960, 이중 예약 0 |

## 설계서와 달라진 점

구현하면서 인터페이스에 더한 것들이다. 경계 인터페이스 변경이므로 팀 확인이 필요하다.

| 항목 | 내용 | 이유 |
| --- | --- | --- |
| `IPolicyProvider.lease()` | 배정 동안 읽기 락을 잡는 임대를 추가 | "배정은 읽기 락, 전환은 쓰기 락"을 인터페이스로 표현 |
| `IAllocationPolicy.name()`, `timing()` | 이름과 판정 시점(IMMEDIATE / AT_CLOSE) 추가 | 워커가 정책 종류를 모른 채 즉시형과 마감형을 다루기 위해 |
| `ICreditLedger.reserve(reservationId, shares)`, `settle(reservationId, charge)` | 인자 추가. 11/19부터 구성원별 몫(`PartyMember`)을 받는다 | 보류와 정산을 같은 예약으로 잇고, 낙찰가를 넘기기 위해. 함께 신청한 예약은 전원이 같은 1인당 금액을 보류하고 똑같이 낸다 |
| `IAdmissionQueue.nack()` | 추가 | 처리 실패 시 재전달과 DLQ 격리 |
| `IReservationLifecycle.hold()`, `find()`, `heldRequests()`, `complete()` | 추가 | 마감형 정책의 보류 저장과 조회 |
| 인터페이스의 `api` 패키지 분리 | 구역마다 `api` 하위 패키지 | 패키지 순환을 없애고 규칙을 테스트로 검사하기 위해 |
| `QueryApi` (Boundary) + `QueryController` | 조회 전용 창구 5개: `GET /policy`, `/slots`, `/credits/me`, `/reservations/{id}`, `/queue` | 화면이 시간표·내 예약·크레딧·전환 상태를 그리려면 읽을 곳이 필요하다. 기존 `Ticket` 계약은 그대로 두었다 |
| `IReservationQuery` + `ReservationQueryService` (가용성 구역) | 예약 한 건 조회, 기간 안의 점유 슬롯 조회 | 상태를 바꾸는 `IReservationLifecycle`과 읽기를 분리. 저장소 인터페이스는 바꾸지 않았다 |
| `IPolicySwitch.phase()` | 전환 상태(ACTIVE / SWITCH_REQUESTED / DRAINING) 조회 | 학생 화면이 "정책 전환 중"을 알리기 위해. 구현은 이미 있던 메서드다 |
| `IAllocationPolicy.maxSlotsPerDay()` | 한 사람이 하루(한국 시각)에 잡을 수 있는 슬롯 수. 선착순 1, 크레딧 베팅 2(11/25) | 하루 이용 한도는 정책마다 달라서 정책이 값을 갖는다. 슬롯이 1시간이라 시간 수와 같다 |
| `IReservationQuery.slotsUsedOnDay()` + `IReservationRepository.findByMemberAndStartsBetween()` (11/19 이름 변경: 신청자가 아니라 구성원 기준) | 사람·날짜별 사용 슬롯 수. 센다: 접수 중·확정·입실·완료·노쇼. 안 센다: 거절·직접 취소 | 한도 판정용. JDBC는 `(user_id, starts_at)` 인덱스를 추가 |
| `AllocationProcessor`의 하루 한도 검사 | 사람·날짜별 락 안에서 센 뒤 넘으면 `DAILY_LIMIT`으로 거절(크레딧은 묶지 않음) | 같은 사람이 여러 방에 동시에 신청해도 한도를 못 넘게 하기 위해. 선착순 하루 1시간, 크레딧 베팅 하루 2시간 |
| `GET /policy`의 `maxSlotsPerDay` | 하루 한도(제한 없으면 null) | 화면이 "이 날 사용 0 / 1시간"을 보여 주고 한도를 넘는 신청을 미리 막기 위해 |
| `ICreditPeriod` / `SemesterCreditPeriod`, `ICreditLedger.renewsAt()`, `GET /credits/me`의 `renewsAt` | 크레딧 갱신 기간(학기: 3/1·9/1 0시 KST). 새 학기 첫 조회 때 잔액을 지급량(100)으로 초기화하고 남은 크레딧은 이월하지 않음. 지난 학기 입찰의 환불·정산은 새 잔액에 반영하지 않음 | 크레딧이 "기간이 끝나야 갱신"되도록. 학기 날짜는 팀 결정 사항(★) |
| `ICreditLedger.held(userId)` | 사용자의 보류 크레딧 합계 | 화면에 "입찰 중 n" 표시. 메모리·JDBC 구현 모두 추가 |
| `ICreditRepository` + `CreditHold` (11/19) | 크레딧 계정·보류의 저장소. `CreditLedger`는 규칙만, 저장은 `InMemoryCreditRepository`·`JdbcCreditRepository`(Device) | 같은 크레딧 규칙이 메모리 장부와 `JdbcCreditLedger`에 두 벌 있던 것을 한 벌로 |
| `ISwitchStateStore.saveStatus()` / `findStatus()` (11/19) | 전환 기록을 Device(메모리·Redis)에 저장 | `PolicySwitchManager`의 메모리 Map에 있어 재시작·다중 서버에서 기록이 사라지던 문제 |
| `AllocationProcessor` 분리 (11/19) | 구성원 검사 `PartyGuard`(처음 이름 DailyQuotaGuard), 판정 `SlotDecider`, 마감 일괄 배정 `CloseTimeAllocator`(모두 성능 구역 안) | 한 클래스에 바뀌는 이유가 셋 섞여 있었다. 생성자와 공개 메서드는 그대로 |
| 함께 신청 (11/19 회의) | `PartyMember`(학번·1인당 크레딧·우선순위), `IAuthPort.lookupStudent()`, `TeamRanking`, `IReservationQuery.noShowsBetween()`, `ICreditPeriod.startOf()`, `SubmitCommand.companions`, `ReservationRequest/Reservation.party`, `IReservationQuery.activeAt()`, `ICreditRepository.holdsOf()`, `GET /policy`의 `minPartySize`, `GET /reservations/{id}`의 `members`(동행도 조회 가능), RDS `reservation_member` 표와 `credit_hold` 복합 PK | 최소 2명, 재학생 확인, 같은 시간 중복 금지, 팀 합계 순위(동점: 패널티 → 인원), 전원 같은 금액, 전원 알림. 구성원 검사는 정책 밖(PartyGuard·CreditLedger)에 두고, 크레딧 베팅 정책은 순위 규칙(`TeamRanking`)만 쓰도록 바꿨다 |
| 체크인 허용 시간·호실·이용 종료 (4번) | `IReservationLifecycle.checkIn(id, roomId, at)`, `TransitionResult`에 `TOO_EARLY`·`TOO_LATE`·`WRONG_ROOM`, `ICheckIn.checkIn(id, roomId, at)`, `SlotId.endsAt()`, `DelayedMessage.Type.SLOT_END`, `OutboxEntry.endTimer()`, `ISessionEndListener`·`SessionEndHandler`, `POST /devices/qr/scan`의 `roomId` | 체크인이 시각을 안 봐서 아침에 오후 예약을 찍으면 노쇼 타이머가 꺼졌다. 이제 시작 10분 전 ~ 유예 종료 전에만 입실되고, 다른 호실 기기면 거절한다. 입실과 함께 종료 타이머를 outbox로 걸어 슬롯이 끝나면 COMPLETED가 된다(DLQ로 가도 재시도). QR 유효시간 토큰은 최종 발표 과제 |

`QueryApi`는 유입 제한(`RateLimiter`)을 걸지 않는다. 화면의 조회 폴링이 예약 접수의 초당 한도를 깎아 먹지 않게
하기 위해서다. 대기열 길이는 큐(Device)가 아는 값이라 조립 루트가 `IntSupplier`로 넘긴다(Boundary가 Device를
직접 보지 않는 규칙 유지). `/slots`는 저장소의 `findOccupiedSlots()` 결과를 기간으로 거르므로, 예약이 많아지면
저장소에 기간 조건 조회를 추가해야 한다.

## 시험 기간 (11/25 결정)

평소에는 선착순, 시험 기간에는 크레딧 베팅으로 배정한다. 정책은 **자리의 날짜**로 고른다(신청 시각이 아니다).

- 관리자가 학사 일정을 보고 `POST /admin/exam-periods {"start":"2026-12-07","end":"2026-12-20","policy":"CREDIT_BETTING"}`로 등록한다.
  시작일은 내일 이후, 다른 기간과 겹치면 400. 등록은 정책 쓰기 락을 잡고 버전을 올린다.
  그 기간에 이미 잡힌 확정 예약은 자동 취소하고 구성원에게 알린다(응답의 `canceled`). `GET`·`DELETE /admin/exam-periods`로 보고 지운다
- 운영 시간은 09:00~22:00(KST). 밖의 슬롯은 400 `OUTSIDE_HOURS`
- 예약은 오늘부터 7일 뒤 날짜까지만 받는다(11/26 결정, `BookingRules.DEFAULT_DAYS_AHEAD`). 넘으면 400 `TOO_FAR_AHEAD`. 시험 기간 날짜는 아래 규칙(하루 전부터)이 먼저 적용된다
- 이미 시작한 시간은 받지 않는다(400 `SLOT_STARTED`). 노쇼로 풀린 자리도 이미 시작한 시간이라 다시 받지 않는다
- 한 사람은 시작 전 예약(접수 중·확정)을 **3개까지만** 들고 있을 수 있다(11/26 결정, `BookingRules.DEFAULT_MAX_PENDING`). 신청자·동행 각자 세고, 넘으면 거절 사유 `PENDING_LIMIT`. 입실·완료·취소·노쇼는 세지 않는다. 날짜를 넘는 한도라 배정 단계의 사람 락을 날짜별(`member:{학번}@{날짜}`)에서 사람별(`member:{학번}`)로 바꿨다
- 시험 기간 자리는 **하루 전 22:00까지** 입찰하고 22:00에 한꺼번에 판정한다. 시작일 자리는 전날이 시험 기간이 아니므로 **당일**부터.
  당일에는 남은 자리를 받는다(10분 마감). 그 밖은 400 `EXAM_NOT_OPEN` / `EXAM_BIDDING_CLOSED`
- 시험 기간 중에 신청해도 기간 밖 날짜는 선착순이다. 크레딧 베팅은 하루 2시간까지
- 크레딧은 시험 기간이 시작될 때마다 100으로 초기화한다(`ExamCreditPeriod`). 노쇼 패널티는 학기 단위 그대로
- 새 정책은 `IAllocationPolicy` 구현 클래스 하나 + `PolicyCatalog`에 한 줄. 메모리·AWS 조립이 같은 목록을 쓴다
- 큐에 있던 신청은 접수 때 찍은 정책 이름과 지금 그 자리의 정책이 다르면 판정하지 않고 `POLICY_CHANGED`로 돌려보낸다(환불).
  이름으로 비교하므로 다른 날짜 때문에 버전만 오른 신청은 그대로 판정한다
- 한계: 평소 정책 전환은 보류 중인 입찰(시험 기간 입찰 포함)이 모두 마감돼야 실행된다. 시험 기간에는 22시 이후에 바꾼다

## 팀이 아직 정하지 않아 임시로 구현한 것

값은 `SystemConfig`(코어)와 `application.yml`(app)에서 바꾼다.

| 항목 | 임시 구현 |
| --- | --- |
| 베팅 마감 일괄 배정을 누가 언제 실행하나 | 요청을 REQUESTED로 저장하고 지연 메시지(SLOT_CLOSE)를 걸어 `AllocationProcessor.onSlotClose`가 슬롯별로 한 번에 배정. 마감 시각은 `BookingRules.closeAt`: 시험 기간 내일 자리는 오늘 22:00, 그 밖은 첫 요청 + 접수 창 길이 |
| 접수 창 길이 | 10분 |
| 학기당 크레딧 지급량 | 100 |
| 낙찰가 방식 | 자기 입찰가(FIRST_PRICE). SECOND_PRICE로 바꿀 수 있다 |
| 크레딧 정산·환불 시점 | 입찰 시 보류, 낙찰 시 차감, 탈락·보류 중 취소 시 환불. 확정 뒤 취소와 노쇼는 환불하지 않음 |
| 전환 3단계(비우기)와 5단계(pause)의 순서 | 설계서 순서대로 하되, 2초 안에 0건이 안 되면 pause를 먼저 건다 |
| 임대(30초) 만료 시 복구 | 소비를 재개하고 ACTIVE로 되돌린다. 정책은 바뀌지 않은 것으로 본다 |
| 유입 제한 값 | 사용자별 초당 5건, 전역 초당 5,000건 |
| DLQ 최대 수신 횟수 | 3 |
| RDS 엔진, 실행 환경(ECS Fargate / EC2) | 미정. 스키마는 MySQL·PostgreSQL 공용 문법 |

## app 모듈 상태

`app` 모듈은 Maven Central에 접속할 수 없는 환경에서 작성했다. 지금까지 확인한 것과 못 한 것은 다음과 같다.

- 확인함: 코어 클래스를 부르는 모든 호출(생성자 인자, 메서드 이름·타입)이 코어와 맞는다. 일부러 틀린 호출을 넣어
  이 검사가 오류를 잡는지도 확인했다. SQL 컬럼 17개와 INSERT 값 17개, schema.sql의 컬럼 이름도 대조했다.
- 못 함: Spring Boot·AWS SDK·Redisson이 실제로 내려받아져 컴파일되는지, 서버가 뜨는지. 이 부분은 라이브러리
  API를 코드로 읽어 대조한 것뿐이다.

2026-11-16에 맥북(JDK 21)에서 `./gradlew :app:bootRun`으로 기본 프로필이 뜨고 `POST /reservations`가 202를
돌려주는 것까지 확인했다. 그 뒤에 추가한 `QueryController`·`QueryApi` 빈 등록은 같은 방식의 컴파일 검사와, 실제
컨트롤러 클래스를 JDK 내장 HTTP 서버에 얹은 검증용 서버(저장소에는 없음)로만 확인했다. Spring으로는 아직 띄워 보지
않았으므로 `./gradlew :app:bootRun` 뒤 위의 조회 호출 예시로 확인한다.

처음 한 번은 인터넷이 되는 환경에서 아래 순서로 확인한다. 컴파일 오류가 나면 아래 세 곳을 먼저 본다.

```bash
./gradlew :app:compileJava    # 1) 컴파일
./gradlew :app:bootRun        # 2) 기본 프로필로 기동 → 호출 예시로 확인
```

- `app/build.gradle`의 라이브러리 버전 (Spring Boot 3.3.5, AWS SDK BOM 2.29.0, Redisson 3.37.0)
- `SqsAdmissionQueue`의 `messageSystemAttributeNames`, `EventBridgeDelayedMessageScheduler`의 `createSchedule` 호출
- `RedisPolicyStores`의 `RBucket.setIfAbsent`, `compareAndSet`

aws 프로필에 필요한 리소스:

| 리소스 | 용도 | 환경 변수 |
| --- | --- | --- |
| SQS FIFO 큐(고처리량 모드) + FIFO DLQ | 접수 큐. 그룹 ID = 슬롯 키, 중복 제거 ID = 요청 id | `STUDYROOM_ADMISSION_QUEUE_URL` |
| SQS 표준 큐 + DLQ | 노쇼·마감 지연 메시지 수신 | `STUDYROOM_TIMER_QUEUE_URL`, `STUDYROOM_TIMER_QUEUE_ARN` |
| EventBridge Scheduler + IAM 역할 | 예약별 1회성 일정. 역할은 timer 큐에 `sqs:SendMessage` 권한 필요 | `STUDYROOM_SCHEDULER_ROLE_ARN` |
| ElastiCache for Redis | 슬롯 락, 슬롯 캐시, 정책 락, 전환 상태. 전송 중 암호화를 켰다면 주소를 `rediss://`로 쓴다 | `STUDYROOM_REDIS_ADDRESS` |
| 리전·자격 증명 | AWS SDK 기본 공급자 체인이 읽는다 | `AWS_REGION`, 자격 증명 |
| RDS | 예약, outbox, 크레딧. `app/src/main/resources/db/schema.sql` 적용 | `STUDYROOM_DB_URL`, `STUDYROOM_DB_USER`, `STUDYROOM_DB_PASSWORD` |

SSO와 알림은 외부 시스템이라 aws 프로필에서도 가짜 구현을 쓴다. 실제 Adapter가 생기면 `AwsConfig`의
`authPort()`, `notifier()` 두 빈만 바꾼다.

## Gradle 없이 코어 테스트하기

```bash
JUNIT_JAR=/path/junit-4.13.2.jar HAMCREST_JAR=/path/hamcrest-core-1.3.jar ./run-tests.sh
```
