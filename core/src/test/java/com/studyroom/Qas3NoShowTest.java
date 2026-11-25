package com.studyroom;

import com.studyroom.control.availability.OutboxPublisher;
import com.studyroom.control.availability.api.IReservationLifecycle.TransitionResult;
import com.studyroom.domain.NotificationEvent;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** QAS-3: 예약마다 건 1회성 타이머로 노쇼를 해제한다. 누락·중복 0, 건당 조건부 UPDATE 1회. */
public class Qas3NoShowTest {
    @Test
    public void unattendedReservationIsReleasedAfterGrace() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 9);
            String id = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, id));
            assertTrue("확정과 함께 타이머가 걸린다", sys.scheduler.isScheduled(OutboxPublisher.noShowKey(id)));

            // 시작 + 10분 전에는 아무 일도 없다.
            clock.set(slot.startsAt().plus(Duration.ofMinutes(9)));
            assertEquals(0, sys.scheduler.tick());
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, id));

            long updatesBefore = sys.repository.conditionalUpdateCount();
            clock.set(slot.startsAt().plus(Duration.ofMinutes(10)));
            assertEquals(1, sys.scheduler.tick());

            Reservation r = sys.repository.findById(id).orElseThrow();
            assertEquals(ReservationStatus.CANCELED, r.status());
            assertEquals("NO_SHOW", r.reason());
            assertEquals("건당 PK 조건부 UPDATE 1회", 1, sys.repository.conditionalUpdateCount() - updatesBefore);
            assertEquals(1, sys.notifications.count(NotificationEvent.Type.NO_SHOW_RELEASED));
            assertFalse("자리가 풀렸다", sys.slotCache.isTaken(slot));
            assertEquals("전달이 끝난 일정은 남지 않는다", 0, sys.scheduler.pendingCount());
        }
    }

    @Test
    public void expiryAfterCheckInChangesNothing() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 9);
            String id = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);

            clock.set(slot.startsAt().plus(Duration.ofMinutes(3)));
            sys.qrDevice.onRawScan("RSV:" + id, clock.instant());
            assertEquals(TransitionResult.APPLIED, sys.qrDevice.lastResult());
            assertEquals(ReservationStatus.CHECKED_IN, TestSupport.statusOf(sys, id));
            assertFalse("체크인하면 타이머를 지운다", sys.scheduler.isScheduled(OutboxPublisher.noShowKey(id)));

            // 타이머 삭제가 실패했거나 메시지가 이미 떠난 경우: 만료 메시지가 체크인 뒤에 도착한다.
            sys.noShowHandler.onGraceExpired(id);
            sys.noShowHandler.onGraceExpired(id);
            assertEquals(ReservationStatus.CHECKED_IN, TestSupport.statusOf(sys, id));
            assertEquals(0, sys.noShowHandler.releasedCount());
            assertEquals(2, sys.noShowHandler.ignoredCount());
            assertEquals(0, sys.notifications.count(NotificationEvent.Type.NO_SHOW_RELEASED));
        }
    }

    @Test
    public void duplicateExpiryMessageReleasesOnlyOnce() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)).start()) {
            String id = TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "k1", 0);
            sys.noShowHandler.onGraceExpired(id);
            sys.noShowHandler.onGraceExpired(id);
            assertEquals(1, sys.noShowHandler.releasedCount());
            assertEquals(1, sys.noShowHandler.ignoredCount());
            assertEquals(1, sys.notifications.count(NotificationEvent.Type.NO_SHOW_RELEASED));
        }
    }

    @Test
    public void releasedSlotCanBeBookedAgainAndLateCheckInFails() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 9);
            String first = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);
            assertEquals(409, sys.studentApi.reserve("user:b", slot, "k2", 0).status());

            clock.set(slot.startsAt().plus(Duration.ofMinutes(10)));
            sys.scheduler.tick();

            String second = TestSupport.reserveAndWait(sys, "b", slot, "k3", 0);
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, second));

            sys.qrDevice.onTag(first, clock.instant());
            assertEquals("노쇼 처리된 예약은 체크인할 수 없다", TransitionResult.NO_OP, sys.qrDevice.lastResult());
            assertEquals(0, TestSupport.doubleBookedSlots(sys.repository.all()));
        }
    }

    @Test
    public void handlerThatKeepsFailingIsIsolatedInDeadLetterQueue() {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1))) {
            sys.scheduler.onMessage(com.studyroom.domain.DelayedMessage.Type.NO_SHOW, id -> {
                throw new IllegalStateException("boom");
            });
            // DLQ 수신자까지 실패하는 경우(예: DB 장애). 수신자가 성공하면 DLQ에서 지워진다(DeadLetterTest).
            sys.scheduler.onDeadLetter(com.studyroom.domain.DelayedMessage.Type.NO_SHOW, id -> {
                throw new IllegalStateException("boom");
            });
            sys.scheduler.schedule("noshow:x", clock.instant(),
                    new com.studyroom.domain.DelayedMessage(com.studyroom.domain.DelayedMessage.Type.NO_SHOW, "x"));
            for (int i = 0; i < sys.config.maxReceiveCount(); i++) sys.scheduler.tick();
            assertEquals(1, sys.scheduler.deadLetters().size());
            assertEquals(0, sys.scheduler.pendingCount());
        }
    }
}
