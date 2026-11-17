# 스터디룸 예약 프론트엔드

백엔드 `app` 모듈(REST)에 붙는 React + TypeScript + Tailwind CSS 앱이다. 화면은 넷이다.

| 주소 | 화면 | 로그인 |
| --- | --- | --- |
| `#` | 학생 예약: 건물 선택 → 호실 × 시간 시간표 → 예약 드로어 → 내 예약 | 학생 |
| `#admin` | 관리자 정책 콘솔: 현재 정책, 정책 전환과 진행 단계, 실시간 점유 현황 | 관리자 |
| `#kiosk` | QR 키오스크: 입실 코드 스캔(직접 입력으로 흉내) → 즉시 결과 | 없음 |
| `#dev` | 연결 점검: 어댑터가 서버와 맞물리는지 한 줄씩 확인 | 아무나 |

로그인은 개발용 가짜 SSO다. 학번(또는 관리자 아이디)을 넣으면 백엔드 `StubSsoAuthAdapter`와 같은 형식의 토큰
(`user:{학번}:{우선순위}`, `admin:{id}`)을 만들어 쓴다.

## 실행

Node 20 이상이 필요하다. 백엔드를 먼저 띄운다(`./gradlew :app:bootRun`, 8080 포트).

```bash
cd frontend
npm install
npm run dev
```

http://localhost:5173 을 연다. 개발 서버가 `/api` 요청을 `localhost:8080` 으로 넘겨 준다
(백엔드에 CORS 설정이 없어서 같은 출처로 맞춘 것이다).

```bash
npm run typecheck
npm test
npm run build
```

## 데모 빌드 (서버 없이 링크로 여는 판)

```bash
npm run build:demo     # dist-demo/ 에 정적 파일
```

`VITE_DEMO=1` 이면 `services.ts` 가 `createApi` 의 `fetchImpl` 자리에 `src/demo/demoServer.ts` 를 끼운다.
화면과 어댑터 코드는 진짜 서버에 붙는 빌드와 같고, fetch 한 곳만 바뀐다(Adapter 교체).
데모 서버는 코어의 규칙(멱등키, 마감 슬롯 조기 거절 409, 정책 3종의 판정과 사유 코드, 접수 창 일괄 배정,
크레딧 보류·차감·환불, 노쇼 지연 메시지, 정책 전환 409 조건과 단계)을 옮긴 것이다. 스레드·Redis·SQS 대신
"시각이 된 일은 다음 요청 때 처리"하고, 상태는 브라우저 localStorage 에만 둔다. 접수 창은 60초로 줄였다.
동시성 제어, 유입 제한(429), 장애 대응은 흉내 내지 않는다.

화면 주소는 `#admin`, `#kiosk`, `#dev` 처럼 슬래시 없는 이름이다(예전 `#/kiosk` 도 받는다).

## 폴더 구조

```
src/
  App.tsx        주소(#/…)에 따라 화면을 고른다
  app/           머리줄·탭(AppShell), 로그인(SignInPanel), 해시 라우팅, 세션 구독
  views/
    student/     StudentBookingView, Timetable, BookingDrawer, MyReservations, ReservationProgress
    admin/       AdminPolicyConsole (정책 전환 추적, 점유 현황)
    kiosk/       QrKioskView
    shared/      상태 문구
  policy-ui/     정책별 신청 화면 모듈과 레지스트리
    modules/     fcfs · priority · creditBetting · fallback
  domain/        프론트 전용 모델. 화면은 이 타입만 본다
  api/
    ports.ts     화면이 의존하는 창구(인터페이스)
    adapters/    창구의 구현. 백엔드 경로·헤더·응답 변환, 폴링(watchers)이 여기서 끝난다
    http/        fetch 래퍼, 멱등키, 재시도 규칙
    dto/         백엔드 JSON 모양 그대로 (api/ 밖에서는 쓰지 않는다)
    index.ts     조립 루트 createApi()
  auth/          SessionPort 와 개발용 가짜 SSO 구현
  state/         이 브라우저에서 신청한 번호표 보관함(localStorage)
  hooks/         usePolling(주기 조회), useNow(카운트다운)
  catalog/       건물·호실·운영 시간 (프론트 설정)
  lib/           시간(KST)·UUID
  styles/        index.css (전역 규칙과 컴포넌트 클래스)
  dev/           연결 점검 화면
  services.ts    앱이 쓰는 session · api 인스턴스
```

구조도는 `docs/frontend-architecture.puml` 에 있다.

## 데이터가 흐르는 길

학생이 시간표의 빈 칸을 누르고 신청할 때:

1. `StudentBookingView` 가 `GET /policy` 로 읽은 정책 이름을 `policyUIRegistry.resolve(이름)` 에 넘겨 모듈을 받는다.
2. `BookingDrawer` 는 그 모듈의 `Form` 을 그리고, `validate` 로 검증하고, `toSubmission` 으로 보낼 값을 만든다.
3. `api.reservations.reserve` (어댑터)가 `Idempotency-Key` 를 붙여 `POST /reservations` 를 보낸다 → 202 + 번호표.
4. 번호표를 `state/myReservations` 에 적고, `useTrackedReservations` 가 `watchTicket` 으로 지켜본다
   (`GET /tickets/{id}` + `GET /reservations/{id}`, 큐 대기 중에는 `GET /queue`).
5. 상태가 바뀌면 시간표(`GET /slots`)와 크레딧(`GET /credits/me`)을 다시 읽는다. 카드와 드로어가 같은 값을 본다.

## 변경이 닿는 범위

| 바뀌는 것 | 고치는 곳 |
| --- | --- |
| 새 배정 정책 추가 | `policy-ui/modules/` 에 파일 1개, `policy-ui/index.ts` 에 `register` 1줄 |
| 백엔드 응답 모양 변경 | `api/dto/backend.ts`, `api/adapters/mappers.ts` |
| 백엔드 경로 변경 | 해당 `api/adapters/*Adapter.ts` |
| 학교 SSO 연동 | `auth/` 에 `SessionPort` 구현 추가, `services.ts` 에서 교체 |
| 색·곡률·그림자 | `tailwind.config.ts` |

화면 코드에는 `if (policy === 'CREDIT_BETTING')` 같은 분기를 두지 않는다.
`policyUIRegistry.resolve(이름)` 으로 받은 모듈의 `Form`, `validate`, `toSubmission` 을 부른다.

## 백엔드와의 계약

| 창구 | 경로 | 비고 |
| --- | --- | --- |
| `reservations.reserve` | `POST /reservations` | `Idempotency-Key` 자동 첨부. 202 + 번호표 |
| `reservations.getTicket` | `GET /tickets/{id}` | 상태는 PENDING·CONFIRMED·REJECTED·CANCELED 네 가지 |
| `reservations.getDetail` | `GET /reservations/{id}` | 상태 6가지, 방·시간, 입찰액, 입실 마감 시각. 큐 대기 중이면 404 → `null` |
| `reservations.cancel` | `DELETE /reservations/{id}` | |
| `adminPolicy.getActivePolicy` | `GET /admin/policy` | 관리자 전용 |
| `adminPolicy.requestSwitch` | `POST /admin/policy/switch` | 전환 번호는 평문으로 온다. 자동 재시도 없음 |
| `adminPolicy.getSwitchProgress` | `GET /admin/policy/switch/{id}` | |
| `qrDevice.checkIn` · `scanRaw` | `POST /devices/qr/scan` | 본문 `{"payload":"RSV:{id}"}`. 토큰 없이 보낸다 |
| `policyStatus.getStatus` | `GET /policy` | 정책 이름·버전, 판정 시점, 전환 상태, 유예·접수 창 길이 |
| `slotBoard.getTaken` | `GET /slots?roomId=A,B&from=&to=` | 차 있는 슬롯만 온다 |
| `credits.getBalance` | `GET /credits/me` | 쓸 수 있는 크레딧과 입찰로 묶인 크레딧 |
| `queue.getLoad` | `GET /queue` | 전체 대기 요청 수 |

표의 마지막 네 줄(조회)은 결과가 `Capability` 로 온다. 이 경로가 없는 예전 서버에서는 404 가 오고, 어댑터는
`{ supported: false }` 를 돌려준다. 화면은 값을 지어내지 않고 "확인할 수 없음"으로 표시한다.

백엔드가 주지 않아서 화면이 하지 않는 것:

- **내 예약 목록.** 번호표 id 로 한 건씩만 읽을 수 있다. 그래서 신청할 때 받은 번호표를 브라우저(localStorage)에
  적어 둔다. 다른 기기에서 신청한 예약은 보이지 않는다.
- **내 앞의 대기 순번과 예상 시간.** 큐(SQS FIFO)는 순번을 알려 주지 않는다. `GET /queue` 는 전체 대기 수다.
- **마감형 정책의 정확한 결과 시각.** 접수 창은 그 슬롯의 첫 신청부터 열리는데, 그 시각을 내려 주지 않는다.
  화면은 "늦어도 (내 신청 시각 + 접수 창 길이)"로만 말한다.
- **키오스크에서 예약의 호실·시간.** 기기용 웹훅은 결과(입실됨/불가/없음)만 준다.
- **우선순위 신청서의 사유·학과 입력.** 신청 본문에 그 필드가 없다. 우선순위는 SSO 토큰이 정한다.
- **호실 목록.** 백엔드에 호실 마스터가 없어 `catalog/campusCatalog.ts` 에 둔다. 이름·정원은 예시값이다.

## 멱등키 규칙

- 같은 사용자가 같은 슬롯을 신청하는 것을 하나의 의도로 보고 같은 키를 쓴다. 연타와 재전송이 한 건으로 처리된다.
- 진행 중인 같은 신청이 있으면 네트워크 요청을 새로 보내지 않는다.
- 번호표가 거절·취소로 끝나거나 서버가 확실히 거절하면(409 등) 키를 버린다. 다음 신청은 새 요청이 된다.
- 연결 실패·시간 초과는 접수 여부를 알 수 없으므로 키를 남기고 같은 키로 다시 보낸다.

## 재시도 규칙

백엔드 `ApiResponse` 의 계약을 따른다. 429 는 `Retry-After` 만큼 기다렸다 재시도,
503 과 연결 실패는 지수 백오프 + 지터, 409 는 재시도하지 않는다.

## 디자인 규칙

`tailwind.config.ts` 가 팔레트·곡률·그림자를 통째로 바꿔 두었다. 그래서 `rounded-2xl`, `shadow-lg`,
`bg-gradient-*`, `backdrop-blur` 는 써도 클래스가 생기지 않는다.

- 면: `white`, `canvas`. 구분은 1px `line`
- 글자: `ink`, `sub`. `mute` 는 비활성 전용
- 포인트 `ku`: 핵심 실행 버튼, 선택된 타임슬롯, 활성 탭 표시에만
- 상태색 `danger`, `warn`: 거절·오류, 대기·주의
- 곡률은 `rounded-md`(6px) 와 `rounded-lg`(8px) 둘뿐. 그림자는 모달·드로어의 `shadow-sm` 하나
- 숫자는 `.num`(고정폭). 로딩은 `.skeleton` 을 실제 칸 크기로

확정·입실 완료 표식(`chip-ok`, `banner-ok`)과 키오스크의 입실 성공 면도 `ku` 틴트를 쓴다. 성공 상태를
나타내는 용도이고, 위 세 곳 밖에서 포인트 색을 쓰는 유일한 예외다.

`#036B3F` 는 요청받은 값을 그대로 넣었다. 학교는 2025년에 새 UI 를 내며 메인 색을 '헤리티지 그린'으로 정했는데,
공개된 자료에서 그 색의 HEX 값을 찾지 못해 대조하지 못했다.

## 확인한 범위

- 타입 검사, 프로덕션 빌드, 단위 테스트 49개(가짜 fetch 로 어댑터·멱등키·재시도·감시기·레지스트리·보관함, 데모 서버 규칙 검증).
- 코어 로직과 실제 컨트롤러 클래스를 JDK 내장 HTTP 서버에 얹은 검증용 서버에 개발 서버를 붙이고, 브라우저 자동화로
  다음을 확인했다(2026-11-17): 선착순 신청 → 확정, 다른 학생 화면의 마감 표시, 409 배너, 키오스크 입실과
  카드의 "입실 완료" 전환, 관리자 정책 전환(버전 1 → 2), 열려 있던 화면의 신청서가 베팅으로 교체, 초과 입찰 검증,
  입찰 → 배정 대기 → 낙찰, 접수 창이 열린 동안의 전환 거절(409), 노쇼 자동 해제, 390px 화면.
- Spring 으로 띄운 서버(`./gradlew :app:bootRun`)에는 아직 붙여 보지 않았다. JSON 직렬화(Jackson)와 요청 바인딩은
  검증용 서버가 흉내 낸 것이므로, 서버를 띄운 뒤 `#/dev` 의 "연결 확인"으로 먼저 확인한다.
