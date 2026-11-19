package com.studyroom.control.availability;

import com.studyroom.control.availability.api.*;
import com.studyroom.control.modifiable.api.ICreditLedger;
import com.studyroom.control.performance.api.CacheUnavailableException;
import com.studyroom.control.performance.api.ISlotStatus;
import com.studyroom.domain.AllocationDecision;
import com.studyroom.domain.NotificationEvent;
import com.studyroom.domain.OutboxEntry;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 예약의 라이프사이클 관리자. 상태별 규칙은 ReservationState(State 패턴)에 있고,
 * 여기서는 "현재 상태가 허용하면 조건부 갱신한다"만 한다.
 *
 * 크레딧 규칙(팀 미정이라 임시): 보류 요청이 취소·거절되면 환불, 낙찰되면 차감,
 * 확정 뒤의 취소와 노쇼는 환불하지 않는다. 함께 신청한 예약은 구성원마다 자기 몫을 보류한다.
 * 알림은 신청자뿐 아니라 등록된 구성원 전원에게 간다.
 */
public final class ReservationLifecycle implements IReservationLifecycle {
    private final IReservationRepository repository;
    private final OutboxPublisher publisher;
    private final IDelayedMessage delayed;
    private final ISlotStatus slotStatus;
    private final ICreditLedger credits;
    private final Clock clock;
    private final Duration grace;

    public ReservationLifecycle(IReservationRepository repository, OutboxPublisher publisher,
                                IDelayedMessage delayed, ISlotStatus slotStatus, ICreditLedger credits,
                                Clock clock, Duration grace) {
        this.repository = repository;
        this.publisher = publisher;
        this.delayed = delayed;
        this.slotStatus = slotStatus;
        this.credits = credits;
        this.clock = clock;
        this.grace = grace;
    }

    @Override
    public HoldResult hold(ReservationRequest q, int policyVersion, String policyName) {
        if (repository.findById(q.id()).isPresent()) return HoldResult.DUPLICATE;
        if (q.bidAmount() > 0 && !credits.reserve(q.id(), q.party())) {
            reject(q, "INSUFFICIENT_CREDIT", policyVersion, policyName);
            return HoldResult.REJECTED;
        }
        try {
            repository.saveRequested(Reservation.from(q, ReservationStatus.REQUESTED, null,
                    policyVersion, policyName, 0, clock.instant()));
            return HoldResult.HELD;
        } catch (DuplicateRequestException e) {
            return HoldResult.DUPLICATE;
        }
    }

    @Override
    public ConfirmResult confirm(ReservationRequest q, AllocationDecision d, int policyVersion, String policyName) {
        Instant now = clock.instant();
        List<OutboxEntry> outbox = new ArrayList<>();
        outbox.add(OutboxEntry.noShowTimer(q.id(), q.slot().startsAt().plus(grace)));
        outbox.addAll(notifyMembers(q.id(), q.userId(), q.memberIds(), NotificationEvent.Type.CONFIRMED, "예약이 확정되었습니다"));
        Optional<Reservation> existing = repository.findById(q.id());
        try {
            if (existing.isPresent()) {
                if (ReservationState.of(existing.get().status()).confirm().isEmpty()) return ConfirmResult.DUPLICATE;
                int rows = repository.confirmRequested(q.id(), policyVersion, policyName, d.charge(), outbox);
                if (rows == 0) return ConfirmResult.DUPLICATE;
                credits.settle(q.id(), d.charge());
            } else {
                repository.saveConfirmed(Reservation.from(q, ReservationStatus.CONFIRMED, null,
                        policyVersion, policyName, d.charge(), now), outbox);
            }
        } catch (SlotAlreadyTakenException e) {
            // 큐·락을 통과했더라도 DB 유니크 제약이 마지막에 막는다.
            reject(q, "SLOT_TAKEN", policyVersion, policyName);
            return ConfirmResult.SLOT_TAKEN;
        } catch (DuplicateRequestException e) {
            return ConfirmResult.DUPLICATE;
        }
        publisher.publishAll(outbox);
        return ConfirmResult.CONFIRMED;
    }

    @Override
    public void reject(ReservationRequest q, String reason, int policyVersion, String policyName) {
        List<OutboxEntry> outbox = notifyMembers(q.id(), q.userId(), q.memberIds(),
                NotificationEvent.Type.REJECTED, "예약이 거절되었습니다: " + reason);
        Optional<Reservation> existing = repository.findById(q.id());
        int rows = 0;
        if (existing.isPresent()) {
            if (ReservationState.of(existing.get().status()).reject().isPresent()) {
                rows = repository.updateStatusIf(q.id(), ReservationStatus.REQUESTED, ReservationStatus.REJECTED, reason, outbox);
            }
        } else {
            try {
                repository.saveRejected(Reservation.from(q, ReservationStatus.REJECTED, reason,
                        policyVersion, policyName, 0, clock.instant()), outbox);
                rows = 1;
            } catch (DuplicateRequestException ignored) {
                // 이미 기록된 요청이다.
            }
        }
        if (rows == 1) {
            credits.refund(q.id());
            publisher.publishAll(outbox);
        }
    }

    @Override
    public TransitionResult checkIn(String id, Instant at) {
        Optional<Reservation> found = repository.findById(id);
        if (found.isEmpty()) return TransitionResult.NOT_FOUND;
        Optional<ReservationStatus> next = ReservationState.of(found.get().status()).checkIn();
        if (next.isEmpty()) return TransitionResult.NO_OP;
        int rows = repository.updateStatusIf(id, ReservationStatus.CONFIRMED, next.get(), null, List.of());
        if (rows == 0) return TransitionResult.NO_OP;
        cancelTimerQuietly(id);
        return TransitionResult.APPLIED;
    }

    @Override
    public TransitionResult cancel(String id, String reason) {
        Optional<Reservation> found = repository.findById(id);
        if (found.isEmpty()) return TransitionResult.NOT_FOUND;
        Reservation r = found.get();
        Optional<ReservationStatus> next = ReservationState.of(r.status()).cancel();
        if (next.isEmpty()) return TransitionResult.NO_OP;
        List<OutboxEntry> outbox = notifyMembers(id, r.userId(), r.memberIds(), NotificationEvent.Type.CANCELED, "예약이 취소되었습니다");
        int rows = repository.updateStatusIf(id, r.status(), next.get(), reason, outbox);
        if (rows == 0) return TransitionResult.NO_OP;
        if (r.status() == ReservationStatus.REQUESTED) credits.refund(id);
        if (r.status() == ReservationStatus.CONFIRMED) {
            releaseSlotQuietly(r.slot());
            cancelTimerQuietly(id);
        }
        publisher.publishAll(outbox);
        return TransitionResult.APPLIED;
    }

    @Override
    public TransitionResult expireIfNotCheckedIn(String id) {
        Optional<Reservation> found = repository.findById(id);
        if (found.isEmpty()) return TransitionResult.NOT_FOUND;
        Reservation r = found.get();
        Optional<ReservationStatus> next = ReservationState.of(r.status()).expire();
        if (next.isEmpty()) return TransitionResult.NO_OP;   // 이미 체크인했거나 취소됐다
        List<OutboxEntry> outbox = notifyMembers(id, r.userId(), r.memberIds(),
                NotificationEvent.Type.NO_SHOW_RELEASED, "미입실로 예약이 해제되었습니다");
        // 건당 PK 조건부 UPDATE 1회. 0행이면 그 사이에 체크인한 것이다.
        int rows = repository.updateStatusIf(id, ReservationStatus.CONFIRMED, next.get(), "NO_SHOW", outbox);
        if (rows == 0) return TransitionResult.NO_OP;
        releaseSlotQuietly(r.slot());
        publisher.publishAll(outbox);
        return TransitionResult.APPLIED;
    }

    @Override
    public TransitionResult complete(String id) {
        Optional<Reservation> found = repository.findById(id);
        if (found.isEmpty()) return TransitionResult.NOT_FOUND;
        Optional<ReservationStatus> next = ReservationState.of(found.get().status()).complete();
        if (next.isEmpty()) return TransitionResult.NO_OP;
        int rows = repository.updateStatusIf(id, ReservationStatus.CHECKED_IN, next.get(), null, List.of());
        if (rows == 1) releaseSlotQuietly(found.get().slot());
        return rows == 1 ? TransitionResult.APPLIED : TransitionResult.NO_OP;
    }

    @Override
    public Optional<Reservation> find(String id) {
        return repository.findById(id);
    }

    @Override
    public List<ReservationRequest> heldRequests(SlotId slot) {
        return repository.findBySlotAndStatus(slot, ReservationStatus.REQUESTED).stream()
                .sorted(Comparator.comparingLong(Reservation::sequence))
                .map(Reservation::toRequest)
                .toList();
    }

    /**
     * 등록된 구성원 전원에게 같은 알림을 보낸다(회의 결정 11/19). 동행에게는 누가 신청했는지 앞에 붙인다.
     * outbox에 함께 저장되므로 한 명에게 보내다 실패해도 OutboxRelay가 그 사람 것만 다시 보낸다.
     */
    static List<OutboxEntry> notifyMembers(String id, String requester, List<String> members,
                                           NotificationEvent.Type type, String message) {
        List<OutboxEntry> out = new ArrayList<>(members.size());
        for (String m : members) {
            String text = m.equals(requester) ? message : requester + "님이 함께 신청한 " + message;
            out.add(OutboxEntry.notify(id, m, type, text));
        }
        return out;
    }

    private void releaseSlotQuietly(SlotId slot) {
        try {
            slotStatus.release(slot);
        } catch (CacheUnavailableException ignored) {
            // 캐시는 힌트일 뿐이다. 복구 때 DB에서 다시 만든다.
        }
    }

    private void cancelTimerQuietly(String id) {
        try {
            delayed.cancel(OutboxPublisher.noShowKey(id));
        } catch (RuntimeException ignored) {
            // 타이머가 남아 울려도 조건부 갱신이 0행으로 끝난다.
        }
    }
}
