package com.studyroom.control.availability.api;

import com.studyroom.domain.OutboxEntry;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;

import java.util.List;
import java.util.Optional;

/**
 * 예약 저장소. 두 가지 유니크 제약이 최후 방어선이다.
 *  - 예약 id(= 멱등키에서 만든 값): 같은 요청은 한 번만 기록된다
 *  - 슬롯: 슬롯을 차지하는 예약(CONFIRMED, CHECKED_IN)은 슬롯당 하나뿐이다
 * 상태 변경과 outbox는 한 트랜잭션에 저장한다.
 */
public interface IReservationRepository {
    void saveConfirmed(Reservation reservation, List<OutboxEntry> outbox);

    void saveRequested(Reservation reservation);

    void saveRejected(Reservation reservation, List<OutboxEntry> outbox);

    /** REQUESTED → CONFIRMED. @return 바뀐 행 수(0이면 이미 다른 상태) */
    int confirmRequested(String id, int policyVersion, String policyName, int charge, List<OutboxEntry> outbox);

    /** PK 조건부 UPDATE: WHERE id = ? AND status = from. @return 바뀐 행 수(0 또는 1) */
    int updateStatusIf(String id, ReservationStatus from, ReservationStatus to, String reason, List<OutboxEntry> outbox);

    Optional<Reservation> findById(String id);

    /** 한 사람의 예약 중 슬롯 시작 시각이 [from, to) 안에 있는 것 전부(모든 상태). 하루 한도 계산에 쓴다. */
    /** 그 사람이 구성원(신청자 또는 동행)인 예약 중 시작 시각이 [from, to)인 것 */
    List<Reservation> findByMemberAndStartsBetween(String userId, java.time.Instant from, java.time.Instant to);

    List<Reservation> findBySlotAndStatus(SlotId slot, ReservationStatus status);

    /** 지금 차 있는 슬롯 목록. 캐시 재구성에 쓴다. */
    List<SlotId> findOccupiedSlots();

    List<OutboxEntry> findUnpublishedOutbox();

    void markOutboxPublished(String outboxId);
}
