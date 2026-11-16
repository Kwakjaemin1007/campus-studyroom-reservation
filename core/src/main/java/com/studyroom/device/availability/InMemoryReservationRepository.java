package com.studyroom.device.availability;

import com.studyroom.control.availability.api.DuplicateRequestException;
import com.studyroom.control.availability.api.IReservationRepository;
import com.studyroom.control.availability.api.SlotAlreadyTakenException;
import com.studyroom.domain.OutboxEntry;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RDS를 흉내 낸 메모리 저장소. 메서드 하나가 트랜잭션 하나다(synchronized).
 * 유니크 제약 두 개(예약 id, 점유 슬롯)를 실제 DB처럼 지킨다.
 */
public final class InMemoryReservationRepository implements IReservationRepository {
    private final Clock clock;
    private final Map<String, Reservation> rows = new LinkedHashMap<>();
    private final Map<String, String> occupiedSlot = new HashMap<>();   // 슬롯 키 → 예약 id (유니크 인덱스)
    private final Map<String, OutboxEntry> outbox = new LinkedHashMap<>();
    private long conditionalUpdates;

    public InMemoryReservationRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized void saveConfirmed(Reservation r, List<OutboxEntry> entries) {
        if (rows.containsKey(r.id())) throw new DuplicateRequestException(r.id());
        if (occupiedSlot.containsKey(r.slot().key())) throw new SlotAlreadyTakenException(r.slot().key());
        rows.put(r.id(), r);
        occupiedSlot.put(r.slot().key(), r.id());
        addOutbox(entries);
    }

    @Override
    public synchronized void saveRequested(Reservation r) {
        if (rows.containsKey(r.id())) throw new DuplicateRequestException(r.id());
        rows.put(r.id(), r);
    }

    @Override
    public synchronized void saveRejected(Reservation r, List<OutboxEntry> entries) {
        if (rows.containsKey(r.id())) throw new DuplicateRequestException(r.id());
        rows.put(r.id(), r);
        addOutbox(entries);
    }

    @Override
    public synchronized int confirmRequested(String id, int policyVersion, String policyName, int charge,
                                             List<OutboxEntry> entries) {
        Reservation r = rows.get(id);
        if (r == null || r.status() != ReservationStatus.REQUESTED) return 0;
        if (occupiedSlot.containsKey(r.slot().key())) throw new SlotAlreadyTakenException(r.slot().key());
        rows.put(id, r.confirmedBy(policyVersion, policyName, charge, clock.instant()));
        occupiedSlot.put(r.slot().key(), id);
        addOutbox(entries);
        return 1;
    }

    @Override
    public synchronized int updateStatusIf(String id, ReservationStatus from, ReservationStatus to, String reason,
                                           List<OutboxEntry> entries) {
        conditionalUpdates++;
        Reservation r = rows.get(id);
        if (r == null || r.status() != from) return 0;
        if (to.occupiesSlot() && !from.occupiesSlot()) {
            if (occupiedSlot.containsKey(r.slot().key())) throw new SlotAlreadyTakenException(r.slot().key());
            occupiedSlot.put(r.slot().key(), id);
        }
        if (from.occupiesSlot() && !to.occupiesSlot()) occupiedSlot.remove(r.slot().key(), id);
        rows.put(id, r.with(to, reason, clock.instant()));
        addOutbox(entries);
        return 1;
    }

    private void addOutbox(List<OutboxEntry> entries) {
        for (OutboxEntry e : entries) outbox.put(e.id(), e);
    }

    @Override
    public synchronized Optional<Reservation> findById(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public synchronized List<Reservation> findBySlotAndStatus(SlotId slot, ReservationStatus status) {
        List<Reservation> out = new ArrayList<>();
        for (Reservation r : rows.values()) {
            if (r.status() == status && r.slot().equals(slot)) out.add(r);
        }
        return out;
    }

    @Override
    public synchronized List<SlotId> findOccupiedSlots() {
        return occupiedSlot.keySet().stream().map(SlotId::parse).toList();
    }

    @Override
    public synchronized List<OutboxEntry> findUnpublishedOutbox() {
        return List.copyOf(outbox.values());
    }

    @Override
    public synchronized void markOutboxPublished(String outboxId) {
        outbox.remove(outboxId);
    }

    // ---- 아래는 테스트·데모에서 결과를 세기 위한 조회 ----

    public synchronized List<Reservation> all() {
        return List.copyOf(rows.values());
    }

    public synchronized long count(ReservationStatus status) {
        return rows.values().stream().filter(r -> r.status() == status).count();
    }

    public synchronized long conditionalUpdateCount() {
        return conditionalUpdates;
    }
}
