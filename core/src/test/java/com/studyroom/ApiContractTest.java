package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.domain.SlotId;
import com.studyroom.domain.Ticket;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** 앱 오류 계약: 401, 400, 429(Retry-After), 409, 503. */
public class ApiContractTest {
    @Test
    public void errorCodes() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults()).start()) {
            SlotId slot = TestSupport.slot("R1", 9);
            assertEquals(401, sys.studentApi.reserve("bogus", slot, "k", 0).status());
            assertEquals(400, sys.studentApi.reserve("user:a", slot, " ", 0).status());
            assertEquals(400, sys.studentApi.reserve("user:a", slot, "k", -1).status());

            // 사용자별 초당 5건까지. 6번째는 429 + Retry-After.
            clock.advance(Duration.ofSeconds(1));
            for (int i = 0; i < 5; i++) {
                assertEquals(202, sys.studentApi.reserve("user:spam", TestSupport.slot("X" + i, 9), "s" + i, 0).status());
            }
            ApiResponse<Ticket> limited = sys.studentApi.reserve("user:spam", TestSupport.slot("X9", 9), "s9", 0);
            assertEquals(429, limited.status());
            assertEquals(Integer.valueOf(1), limited.retryAfterSeconds());
            // 다른 사용자는 영향이 없고, 1초 뒤에는 다시 된다.
            assertEquals(202, sys.studentApi.reserve("user:other", TestSupport.slot("Y", 9), "o1", 0).status());
            clock.advance(Duration.ofSeconds(1));
            assertEquals(202, sys.studentApi.reserve("user:spam", TestSupport.slot("X9", 9), "s9", 0).status());
            sys.awaitQuiescent(5000);

            // 큐 장애는 503.
            sys.queue.setAvailable(false);
            ApiResponse<Ticket> down = sys.studentApi.reserve("user:b", TestSupport.slot("Z", 9), "z1", 0);
            assertEquals(503, down.status());
            assertNull(down.body());
            sys.queue.setAvailable(true);
        }
    }

    @Test
    public void ticketPollingAndCancel() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults())) {
            SlotId slot = TestSupport.slot("R1", 9);
            ApiResponse<Ticket> accepted = sys.studentApi.reserve("user:a", slot, "k1", 0);
            String id = accepted.body().ticketId();
            assertEquals("워커가 처리하기 전에는 PENDING", Ticket.Status.PENDING, sys.studentApi.getTicket("user:a", id).body().status());

            sys.start();
            sys.awaitQuiescent(5000);
            assertEquals(Ticket.Status.CONFIRMED, sys.studentApi.getTicket("user:a", id).body().status());

            assertEquals(403, sys.studentApi.cancel("user:b", id).status());
            assertEquals(404, sys.studentApi.cancel("user:a", "nope").status());
            assertEquals(200, sys.studentApi.cancel("user:a", id).status());
            assertEquals(409, sys.studentApi.cancel("user:a", id).status());
            assertEquals(Ticket.Status.CANCELED, sys.studentApi.getTicket("user:a", id).body().status());

            // 취소로 풀린 슬롯은 다시 잡을 수 있다.
            String again = TestSupport.reserveAndWait(sys, "b", slot, "k2", 0);
            assertEquals(Ticket.Status.CONFIRMED, sys.intake.getTicket(again).status());
        }
    }
}
