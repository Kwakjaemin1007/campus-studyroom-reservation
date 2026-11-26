package com.studyroom;

import com.studyroom.control.modifiable.api.INotifier;
import com.studyroom.domain.NotificationEvent;
import com.studyroom.domain.ReservationStatus;
import org.junit.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;

/** QAS-4: 알림 공급자를 다른 구현으로 바꿔도 Control 코드는 그대로 동작한다. */
public class Qas4AdapterSwapTest {
    /** 다른 업체의 알림 서비스라고 치자. INotifier만 구현하면 된다. */
    static final class OtherVendorSmsAdapter implements INotifier {
        final List<String> outbox = new CopyOnWriteArrayList<>();

        @Override
        public void notify(String userId, NotificationEvent event) {
            outbox.add("SMS to " + userId + ": " + event.type());
        }
    }

    @Test
    public void swappingTheNotifierNeedsNoChangeOutsideTheAdapter() throws Exception {
        OtherVendorSmsAdapter sms = new OtherVendorSmsAdapter();
        try (StudyRoomSystem sys = new StudyRoomSystem(TestSupport.liveClock(), SystemConfig.defaults().withMinPartySize(1), sms).start()) {
            String id = TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "k1", 0);
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, id));
            assertEquals(List.of("SMS to a: CONFIRMED"), sms.outbox);
            assertEquals("예전 Adapter로는 아무것도 가지 않는다", 0, sys.notifications.sent().size());
        }
    }
}
