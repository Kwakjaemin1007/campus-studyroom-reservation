-- RDS 스키마 (MySQL 8 / PostgreSQL 공용 문법). aws 프로필로 띄우기 전에 한 번 적용한다.
-- 접속 시간대는 UTC로 맞춘다.

CREATE TABLE reservation (
    id                VARCHAR(64)  NOT NULL PRIMARY KEY,   -- (userId, 멱등키)에서 만든 값: 멱등키 영구 기록
    idempotency_key   VARCHAR(128) NOT NULL,
    user_id           VARCHAR(64)  NOT NULL,
    room_id           VARCHAR(64)  NOT NULL,
    starts_at         TIMESTAMP(6) NOT NULL,
    slot_key          VARCHAR(128) NOT NULL,
    -- 슬롯을 차지하는 상태(CONFIRMED, CHECKED_IN)일 때만 slot_key와 같은 값, 아니면 NULL.
    -- NULL은 유니크 제약에 걸리지 않으므로 "슬롯당 점유 예약 1건"이 DB에서 보장된다.
    occupied_slot_key VARCHAR(128) NULL,
    status            VARCHAR(16)  NOT NULL,
    reason            VARCHAR(64)  NULL,
    policy_version    INT          NOT NULL,
    policy_name       VARCHAR(32)  NOT NULL,
    bid_amount        INT          NOT NULL,
    charged_amount    INT          NOT NULL,
    priority          INT          NOT NULL,
    sequence_no       BIGINT       NOT NULL,
    requested_at      TIMESTAMP(6) NOT NULL,
    updated_at        TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_reservation_occupied_slot UNIQUE (occupied_slot_key)
);

CREATE INDEX idx_reservation_slot_status ON reservation (slot_key, status);
CREATE INDEX idx_reservation_user_starts ON reservation (user_id, starts_at);

-- 함께 이용하는 사람(신청자 포함, 최소 2명). 같은 시간 중복 검사와 사람별 하루 한도는 이 표로 센다.
CREATE TABLE reservation_member (
    reservation_id VARCHAR(64) NOT NULL,
    user_id        VARCHAR(64) NOT NULL,   -- 학번
    bid_amount     INT         NOT NULL,   -- 이 사람이 건 크레딧(베팅이 아니면 0)
    member_order   INT         NOT NULL,   -- 0 = 신청자
    starts_at      TIMESTAMP(6) NOT NULL,  -- 조회용으로 예약의 시작 시각을 복사
    PRIMARY KEY (reservation_id, user_id)
);
CREATE INDEX idx_member_user_starts ON reservation_member (user_id, starts_at);

CREATE TABLE outbox (
    id             VARCHAR(128) NOT NULL PRIMARY KEY,
    kind           VARCHAR(32)  NOT NULL,
    reservation_id VARCHAR(64)  NOT NULL,
    user_id        VARCHAR(64)  NULL,
    fire_at        TIMESTAMP(6) NULL,
    event_type     VARCHAR(32)  NULL,
    message        VARCHAR(255) NULL,
    created_at     TIMESTAMP(6) NOT NULL
);

-- period_id: 크레딧이 갱신되는 기간(학기, 예: 2026-2). 기간이 바뀐 계정은 처음 쓸 때 지급량으로 초기화된다.
CREATE TABLE credit_account (
    user_id   VARCHAR(64) NOT NULL PRIMARY KEY,
    balance   INT         NOT NULL,
    period_id VARCHAR(16) NOT NULL
);

-- 함께 신청한 예약은 구성원마다 자기 몫을 보류한다: 예약 × 구성원이 한 행
CREATE TABLE credit_hold (
    reservation_id VARCHAR(64) NOT NULL,
    user_id        VARCHAR(64) NOT NULL,
    amount         INT         NOT NULL,
    period_id      VARCHAR(16) NOT NULL,
    PRIMARY KEY (reservation_id, user_id)
);
CREATE INDEX idx_credit_hold_user ON credit_hold (user_id, period_id);
