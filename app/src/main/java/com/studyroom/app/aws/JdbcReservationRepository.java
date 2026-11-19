package com.studyroom.app.aws;

import com.studyroom.control.availability.api.DuplicateRequestException;
import com.studyroom.control.availability.api.IReservationRepository;
import com.studyroom.control.availability.api.SlotAlreadyTakenException;
import com.studyroom.domain.NotificationEvent;
import com.studyroom.domain.OutboxEntry;
import com.studyroom.domain.PartyMember;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 예약 저장소의 RDS 구현 (MySQL / PostgreSQL 공용 SQL).
 * 슬롯 유니크 제약은 occupied_slot_key 컬럼으로 건다: 점유 상태일 때만 값이 있고 아니면 NULL이다.
 * 상태 변경과 outbox INSERT는 한 트랜잭션이다.
 */
public final class JdbcReservationRepository implements IReservationRepository {
    private static final String INSERT = "INSERT INTO reservation (id, idempotency_key, user_id, room_id, starts_at, slot_key, "
            + "occupied_slot_key, status, reason, policy_version, policy_name, bid_amount, charged_amount, priority, "
            + "sequence_no, requested_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final RowMapper<Reservation> ROW = (rs, n) -> new Reservation(
            rs.getString("id"), rs.getString("idempotency_key"), rs.getString("user_id"),
            new SlotId(rs.getString("room_id"), rs.getTimestamp("starts_at").toInstant()),
            ReservationStatus.valueOf(rs.getString("status")), rs.getString("reason"),
            rs.getInt("policy_version"), rs.getString("policy_name"),
            rs.getInt("bid_amount"), rs.getInt("charged_amount"), rs.getInt("priority"), rs.getLong("sequence_no"),
            rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), null);

    private static final RowMapper<OutboxEntry> OUTBOX_ROW = (rs, n) -> {
        Timestamp fireAt = rs.getTimestamp("fire_at");
        String type = rs.getString("event_type");
        return new OutboxEntry(rs.getString("id"), OutboxEntry.Kind.valueOf(rs.getString("kind")),
                rs.getString("reservation_id"), rs.getString("user_id"),
                fireAt == null ? null : fireAt.toInstant(),
                type == null ? null : NotificationEvent.Type.valueOf(type), rs.getString("message"));
    };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JdbcReservationRepository(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void saveConfirmed(Reservation r, List<OutboxEntry> outbox) {
        insert(r, outbox);
    }

    @Override
    public void saveRequested(Reservation r) {
        insert(r, List.of());
    }

    @Override
    public void saveRejected(Reservation r, List<OutboxEntry> outbox) {
        insert(r, outbox);
    }

    private void insert(Reservation r, List<OutboxEntry> outbox) {
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update(INSERT, r.id(), r.idempotencyKey(), r.userId(), r.slot().roomId(),
                        Timestamp.from(r.slot().startsAt()), r.slot().key(),
                        r.status().occupiesSlot() ? r.slot().key() : null,
                        r.status().name(), r.reason(), r.policyVersion(), r.policyName(),
                        r.bidAmount(), r.chargedAmount(), r.priority(), r.sequence(),
                        Timestamp.from(r.requestedAt()), Timestamp.from(r.updatedAt()));
                int order = 0;
                for (PartyMember m : r.party()) {
                    jdbc.update("INSERT INTO reservation_member (reservation_id, user_id, bid_amount, member_order, starts_at) "
                            + "VALUES (?,?,?,?,?)", r.id(), m.userId(), m.bid(), order++, Timestamp.from(r.slot().startsAt()));
                }
                insertOutbox(outbox);
            });
        } catch (DuplicateKeyException e) {
            // 어느 유니크 제약에 걸렸는지 가린다: 같은 id가 있으면 중복 요청, 아니면 슬롯이 찬 것이다.
            if (findById(r.id()).isPresent()) throw new DuplicateRequestException(r.id());
            throw new SlotAlreadyTakenException(r.slot().key());
        }
    }

    @Override
    public int confirmRequested(String id, int policyVersion, String policyName, int charge, List<OutboxEntry> outbox) {
        try {
            Integer rows = tx.execute(status -> {
                int n = jdbc.update("UPDATE reservation SET status = 'CONFIRMED', reason = NULL, occupied_slot_key = slot_key, "
                                + "policy_version = ?, policy_name = ?, charged_amount = ?, updated_at = ? "
                                + "WHERE id = ? AND status = 'REQUESTED'",
                        policyVersion, policyName, charge, Timestamp.from(clock.instant()), id);
                if (n == 1) insertOutbox(outbox);
                return n;
            });
            return rows == null ? 0 : rows;
        } catch (DuplicateKeyException e) {
            throw new SlotAlreadyTakenException(id);
        }
    }

    @Override
    public int updateStatusIf(String id, ReservationStatus from, ReservationStatus to, String reason, List<OutboxEntry> outbox) {
        // PK 조건부 UPDATE 한 번. 풀 스캔이 없다.
        String occupied = to.occupiesSlot() ? "slot_key" : "NULL";
        try {
            Integer rows = tx.execute(status -> {
                int n = jdbc.update("UPDATE reservation SET status = ?, reason = ?, occupied_slot_key = " + occupied
                                + ", updated_at = ? WHERE id = ? AND status = ?",
                        to.name(), reason, Timestamp.from(clock.instant()), id, from.name());
                if (n == 1) insertOutbox(outbox);
                return n;
            });
            return rows == null ? 0 : rows;
        } catch (DuplicateKeyException e) {
            throw new SlotAlreadyTakenException(id);
        }
    }

    private void insertOutbox(List<OutboxEntry> outbox) {
        for (OutboxEntry e : outbox) {
            jdbc.update("INSERT INTO outbox (id, kind, reservation_id, user_id, fire_at, event_type, message, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?)",
                    e.id(), e.kind().name(), e.reservationId(), e.userId(),
                    e.fireAt() == null ? null : Timestamp.from(e.fireAt()),
                    e.eventType() == null ? null : e.eventType().name(), e.message(), Timestamp.from(clock.instant()));
        }
    }

    /** 구성원 표를 읽어 붙인다(예약 한 건당 쿼리 한 번, PK 앞부분 인덱스). */
    private Reservation withParty(Reservation r) {
        List<PartyMember> party = jdbc.query(
                "SELECT user_id, bid_amount FROM reservation_member WHERE reservation_id = ? ORDER BY member_order",
                (rs, n) -> new PartyMember(rs.getString("user_id"), rs.getInt("bid_amount")), r.id());
        return party.isEmpty() ? r : r.withParty(party);
    }

    @Override
    public Optional<Reservation> findById(String id) {
        return jdbc.query("SELECT * FROM reservation WHERE id = ?", ROW, id).stream().findFirst().map(this::withParty);
    }

    @Override
    public List<Reservation> findByMemberAndStartsBetween(String userId, Instant from, Instant to) {
        return jdbc.query("SELECT r.* FROM reservation r JOIN reservation_member m ON m.reservation_id = r.id "
                        + "WHERE m.user_id = ? AND m.starts_at >= ? AND m.starts_at < ?",
                ROW, userId, Timestamp.from(from), Timestamp.from(to)).stream().map(this::withParty).toList();
    }

    @Override
    public List<Reservation> findBySlotAndStatus(SlotId slot, ReservationStatus status) {
        return jdbc.query("SELECT * FROM reservation WHERE slot_key = ? AND status = ? ORDER BY sequence_no",
                ROW, slot.key(), status.name()).stream().map(this::withParty).toList();
    }

    @Override
    public List<SlotId> findOccupiedSlots() {
        return jdbc.query("SELECT occupied_slot_key FROM reservation WHERE occupied_slot_key IS NOT NULL",
                (rs, n) -> SlotId.parse(rs.getString(1)));
    }

    @Override
    public List<OutboxEntry> findUnpublishedOutbox() {
        return jdbc.query("SELECT * FROM outbox ORDER BY created_at", OUTBOX_ROW);
    }

    @Override
    public void markOutboxPublished(String outboxId) {
        jdbc.update("DELETE FROM outbox WHERE id = ?", outboxId);
    }

    /** 접수 창이 열려 보류 중인 요청 수. 정책 전환을 막을지 판단할 때 쓴다. */
    public int countRequested() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE status = 'REQUESTED'", Integer.class);
        return n == null ? 0 : n;
    }
}
