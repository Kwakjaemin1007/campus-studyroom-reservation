package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.performance.DeadLetterHandler;
import com.studyroom.control.performance.api.QueueMessage;
import com.studyroom.device.availability.InMemoryDelayedMessageScheduler;
import com.studyroom.device.performance.InMemoryAdmissionQueue;
import com.studyroom.domain.DelayedMessage;
import com.studyroom.domain.NotificationEvent;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.domain.Ticket;
import org.junit.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 끝내 처리하지 못한 메시지(DLQ)를 정리한다 (Dead Letter Channel).
 * 신청이 "처리 중"에 갇히거나 크레딧이 묶이지 않고, 처리 실패로 거절·환불·알림된다.
 */
public class DeadLetterTest {

    @Test
    public void requestThatKeepsFailingIsRejectedAndMemberIsNotified() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 9);
            // 다른 쪽이 슬롯 락을 계속 쥐고 있어 배정이 매번 실패한다(nack).
            assertTrue(sys.slotLock.tryLock(slot.key(), Duration.ofMinutes(1)));

            ApiResponse<Ticket> r = sys.studentApi.reserve("user:a", slot, "k1", 0);
            assertEquals(202, r.status());
            String id = r.body().ticketId();
            TestSupport.await(() -> sys.repository.findById(id).isPresent(), 5000);

            Reservation res = sys.repository.findById(id).orElseThrow();
            assertEquals("최대 수신 횟수 뒤에는 처리 실패로 거절한다", ReservationStatus.REJECTED, res.status());
            assertEquals(DeadLetterHandler.PROCESSING_FAILED, res.reason());
            assertEquals("DLQ에 남지 않는다", 0, sys.queue.deadLetters().size());
            TestSupport.await(() -> sys.notifications.count(NotificationEvent.Type.REJECTED) == 1, 5000);
            assertEquals("티켓이 더는 처리 중이 아니다", Ticket.Status.REJECTED,
                    sys.studentApi.getTicket("user:a", id).body().status());
        }
    }

    @Test
    public void failedSlotCloseRefundsHeldBidsOnlyOnce() throws Exception {
        SystemConfig cfg = SystemConfig.defaults().withMinPartySize(1).withInitialPolicy(CreditBettingPolicy.NAME);
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), cfg).start()) {
            SlotId slot = TestSupport.slot("R1", 9);
            String a = TestSupport.reserveAndWait(sys, "a", slot, "k1", 30);
            String b = TestSupport.reserveAndWait(sys, "b", slot, "k2", 20);
            assertEquals(30, sys.credits.held("a"));

            // 마감 판정 메시지가 DLQ로 갔다. 같은 메시지가 두 번 와도 환불은 한 번이다.
            sys.deadLetterHandler.onSlotCloseFailed(slot.key());
            sys.deadLetterHandler.onSlotCloseFailed(slot.key());

            for (String id : List.of(a, b)) {
                Reservation res = sys.repository.findById(id).orElseThrow();
                assertEquals(ReservationStatus.REJECTED, res.status());
                assertEquals(DeadLetterHandler.PROCESSING_FAILED, res.reason());
            }
            assertEquals(0, sys.credits.held("a"));
            assertEquals(100, sys.credits.balance("a"));
            assertEquals(100, sys.credits.balance("b"));
        }
    }

    @Test
    public void confirmedReservationIsUntouchedByLateDeadLetter() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1)).start()) {
            String id = TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "k1", 0);
            Reservation confirmed = sys.repository.findById(id).orElseThrow();
            assertEquals(ReservationStatus.CONFIRMED, confirmed.status());

            sys.deadLetterHandler.onDeadLetter(confirmed.toRequest());

            assertEquals("이미 확정된 예약은 바뀌지 않는다", ReservationStatus.CONFIRMED,
                    sys.repository.findById(id).orElseThrow().status());
        }
    }

    @Test
    public void queueKeepsDeadLetterWhileHandlerFailsAndRetriesLater() {
        InMemoryAdmissionQueue queue = new InMemoryAdmissionQueue(Clock.systemUTC(), 2, 1);
        AtomicInteger calls = new AtomicInteger();
        List<ReservationRequest> handled = new ArrayList<>();
        queue.onDeadLetter(r -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("DB down");
            handled.add(r);
        });
        ReservationRequest q = new ReservationRequest("r1", "k1", "a", TestSupport.slot("R1", 9),
                TestSupport.T0, 1, 0, 0, List.of());
        queue.enqueue(q);
        for (int i = 0; i < 2; i++) {
            QueueMessage m = queue.poll(1).get(0);
            queue.nack(m);
        }

        assertEquals("수신자가 실패하면 DLQ에 남는다", 1, queue.deadLetters().size());
        assertEquals(0, queue.depth());
        assertEquals(1, queue.retryDeadLetters());
        assertEquals(List.of(q), handled);
        assertEquals(0, queue.deadLetters().size());
    }

    @Test
    public void schedulerHandsDeadLettersToTheHandlerOfThatType() {
        InMemoryDelayedMessageScheduler scheduler =
                new InMemoryDelayedMessageScheduler(Clock.fixed(TestSupport.T0, ZoneOffset.UTC), 2);
        List<String> slotCloseFailed = new ArrayList<>();
        AtomicInteger noShowDead = new AtomicInteger();
        scheduler.onMessage(DelayedMessage.Type.SLOT_CLOSE, k -> {
            throw new IllegalStateException("slot is busy");
        });
        scheduler.onDeadLetter(DelayedMessage.Type.SLOT_CLOSE, slotCloseFailed::add);
        scheduler.onDeadLetter(DelayedMessage.Type.NO_SHOW, k -> noShowDead.incrementAndGet());
        scheduler.schedule("slot-close:R1", TestSupport.T0, new DelayedMessage(DelayedMessage.Type.SLOT_CLOSE, "R1@x"));

        scheduler.tick();
        assertTrue("첫 실패는 다시 시도한다", slotCloseFailed.isEmpty());
        scheduler.tick();

        assertEquals("종류에 맞는 수신자만 받는다", List.of("R1@x"), slotCloseFailed);
        assertEquals(0, noShowDead.get());
        assertEquals(0, scheduler.deadLetters().size());
    }
}
