package com.studyroom;

import com.studyroom.control.availability.OutboxPublisher;
import com.studyroom.control.availability.api.IReservationLifecycle.TransitionResult;
import com.studyroom.domain.DelayedMessage;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 4번 수정: 체크인 허용 시간, 호실 확인, 이용 종료(COMPLETED).
 *  - 입실은 시작 10분 전 ~ 유예 종료 전에만 된다. 아침에 오후 예약을 찍어 노쇼 타이머를 끄지 못한다.
 *  - 다른 호실 기기에서 찍으면 거절한다.
 *  - 입실하면 슬롯 종료 시각에 이용 종료 메시지가 와서 COMPLETED가 된다(중복·DLQ 재전달에도 한 번).
 */
public class CheckInWindowTest {
    private static final Duration GRACE = SystemConfig.defaults().grace();

    @Test
    public void checkInTooEarlyIsRejectedAndKeepsNoShowTimer() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 5);
            String id = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);

            // 같은 날 오전(시작 5시간 전)에 찍는다.
            clock.set(slot.startsAt().minus(Duration.ofHours(5)));
            assertEquals(TransitionResult.TOO_EARLY, sys.qrDevice.onRawScan("RSV:" + id, clock.instant()));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, id));
            assertTrue("노쇼 타이머는 그대로 남는다", sys.scheduler.isScheduled(OutboxPublisher.noShowKey(id)));

            // 10분 1초 전도 이르다.
            clock.set(slot.startsAt().minus(Duration.ofMinutes(10)).minusSeconds(1));
            assertEquals(TransitionResult.TOO_EARLY, sys.qrDevice.onRawScan("RSV:" + id, clock.instant()));

            // 그래서 오지 않으면 그대로 노쇼로 풀린다.
            clock.set(slot.startsAt().plus(GRACE));
            sys.scheduler.tick();
            assertEquals(ReservationStatus.CANCELED, TestSupport.statusOf(sys, id));
        }
    }

    @Test
    public void checkInOpensTenMinutesBeforeStart() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 5);
            String id = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);

            clock.set(slot.startsAt().minus(Duration.ofMinutes(10)));
            assertEquals(TransitionResult.APPLIED, sys.qrDevice.onRawScan("RSV:" + id, clock.instant()));
            assertEquals(ReservationStatus.CHECKED_IN, TestSupport.statusOf(sys, id));
            assertFalse("입실하면 노쇼 타이머를 지운다", sys.scheduler.isScheduled(OutboxPublisher.noShowKey(id)));
        }
    }

    @Test
    public void checkInAfterGraceIsRejectedEvenBeforeNoShowMessageArrives() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 5);
            String id = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);

            // 유예가 끝났지만 노쇼 메시지는 아직 처리되지 않은 틈(tick 전).
            clock.set(slot.startsAt().plus(GRACE));
            assertEquals(TransitionResult.TOO_LATE, sys.qrDevice.onRawScan("RSV:" + id, clock.instant()));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, id));

            sys.scheduler.tick();
            assertEquals("노쇼 해제가 이긴다", ReservationStatus.CANCELED, TestSupport.statusOf(sys, id));
            assertEquals("해제된 뒤에는 상태 때문에 거절", TransitionResult.NO_OP,
                    sys.qrDevice.onRawScan("RSV:" + id, clock.instant()));
        }
    }

    @Test
    public void scanFromAnotherRoomIsRejected() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 5);
            String id = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);
            clock.set(slot.startsAt());

            assertEquals(TransitionResult.WRONG_ROOM, sys.qrDevice.onRawScan("RSV:" + id, "R2", clock.instant()));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, id));

            assertEquals(TransitionResult.APPLIED, sys.qrDevice.onRawScan("RSV:" + id, "R1", clock.instant()));
            assertEquals(ReservationStatus.CHECKED_IN, TestSupport.statusOf(sys, id));
        }
    }

    @Test
    public void checkedInReservationCompletesAtSlotEndOnlyOnce() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 5);
            String id = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);
            clock.set(slot.startsAt().plus(Duration.ofMinutes(3)));
            assertEquals(TransitionResult.APPLIED, sys.qrDevice.onRawScan("RSV:" + id, clock.instant()));
            assertTrue("입실하면 이용 종료 타이머가 걸린다", sys.scheduler.isScheduled(OutboxPublisher.endKey(id)));

            // 종료 1초 전에는 아직 입실 상태다.
            clock.set(slot.endsAt().minusSeconds(1));
            sys.scheduler.tick();
            assertEquals(ReservationStatus.CHECKED_IN, TestSupport.statusOf(sys, id));

            clock.set(slot.endsAt());
            assertEquals(1, sys.scheduler.tick());
            assertEquals(ReservationStatus.COMPLETED, TestSupport.statusOf(sys, id));

            // 같은 메시지가 늦게 한 번 더 온다.
            sys.sessionEndHandler.onSlotEnded(id);
            assertEquals(ReservationStatus.COMPLETED, TestSupport.statusOf(sys, id));
            assertEquals(1, sys.sessionEndHandler.completedCount());
            assertEquals(1, sys.sessionEndHandler.ignoredCount());
            assertEquals("전달이 끝난 일정은 남지 않는다", 0, sys.scheduler.pendingCount());
        }
    }

    @Test
    public void reservationThatWasNeverCheckedInGetsNoEndTimer() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 5);
            String id = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);
            assertFalse(sys.scheduler.isScheduled(OutboxPublisher.endKey(id)));

            // 종료 메시지가 잘못 와도 입실하지 않은 예약은 바뀌지 않는다.
            sys.sessionEndHandler.onSlotEnded(id);
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, id));
        }
    }

    @Test
    public void endMessageThatKeepsFailingIsRetriedFromDeadLetterQueue() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 5);
            String id = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);
            clock.set(slot.startsAt());
            assertEquals(TransitionResult.APPLIED, sys.qrDevice.onRawScan("RSV:" + id, clock.instant()));

            // 평소 경로가 계속 실패한다(예: 일시적 장애). DLQ 수신자는 그대로 SessionEndHandler다.
            sys.scheduler.onMessage(DelayedMessage.Type.SLOT_END, rid -> {
                throw new IllegalStateException("boom");
            });
            clock.set(slot.endsAt());
            for (int i = 0; i < sys.config.maxReceiveCount(); i++) sys.scheduler.tick();

            assertEquals("DLQ에서 다시 처리해 종료된다", ReservationStatus.COMPLETED, TestSupport.statusOf(sys, id));
            assertEquals(0, sys.scheduler.deadLetters().size());
        }
    }
}
