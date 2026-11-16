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

CREATE TABLE credit_account (
    user_id VARCHAR(64) NOT NULL PRIMARY KEY,
    balance INT         NOT NULL
);

CREATE TABLE credit_hold (
    reservation_id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id        VARCHAR(64) NOT NULL,
    amount         INT         NOT NULL
);
