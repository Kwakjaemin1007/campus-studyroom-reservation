package com.studyroom;

import com.studyroom.control.availability.OutboxPublisher;
import com.studyroom.domain.NotificationEvent;
import com.studyroom.domain.ReservationStatus;
import org.junit.Test;

import java.time.Clock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Outbox: 커밋 직후 타이머·알림 발행에 실패해도 Relay가 다시 발행한다. */
public class OutboxRelayTest {
    @Test
    public void relayRepublishesWhatFailedAtConfirmTime() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1)).start()) {
            sys.scheduler.setAvailable(false);
            sys.notifications.failNext(1);

            String id = TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "k1", 0);
            assertEquals("예약 자체는 확정된다", ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, id));
            assertEquals(2, sys.repository.findUnpublishedOutbox().size());
            assertEquals(0, sys.notifications.count(NotificationEvent.Type.CONFIRMED));

            sys.scheduler.setAvailable(true);
            assertFalse(sys.scheduler.isScheduled(OutboxPublisher.noShowKey(id)));

            assertEquals(2, sys.outboxRelay.relayOnce());
            assertTrue(sys.scheduler.isScheduled(OutboxPublisher.noShowKey(id)));
            assertEquals(1, sys.notifications.count(NotificationEvent.Type.CONFIRMED));
            assertEquals(0, sys.repository.findUnpublishedOutbox().size());

            // 다시 돌려도 중복 발행은 없다.
            assertEquals(0, sys.outboxRelay.relayOnce());
            assertEquals(1, sys.notifications.count(NotificationEvent.Type.CONFIRMED));
        }
    }
}
