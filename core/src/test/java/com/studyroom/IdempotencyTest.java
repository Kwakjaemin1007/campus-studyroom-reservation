package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.Ticket;
import org.junit.Test;

import java.time.Clock;

import static org.junit.Assert.assertEquals;

/** D10: 같은 멱등키의 재시도는 예약을 하나만 만들고, 같은 티켓과 같은 결과를 돌려준다. */
public class IdempotencyTest {
    @Test
    public void retryWithSameKeyCreatesOneReservation() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1))) {
            // 워커를 띄우기 전에 두 번 넣는다: 큐의 중복 제거가 한 건만 남긴다.
            ApiResponse<Ticket> first = sys.studentApi.reserve("user:a", TestSupport.slot("R1", 9), "same-key", 0);
            ApiResponse<Ticket> retry = sys.studentApi.reserve("user:a", TestSupport.slot("R1", 9), "same-key", 0);
            assertEquals(first.body().ticketId(), retry.body().ticketId());
            assertEquals(1, sys.queue.depth());

            sys.start();
            sys.awaitQuiescent(5000);
            assertEquals(1, sys.repository.all().size());

            // 처리가 끝난 뒤의 재시도: DB에 남은 결과를 그대로 돌려준다.
            ApiResponse<Ticket> late = sys.studentApi.reserve("user:a", TestSupport.slot("R1", 9), "same-key", 0);
            assertEquals(202, late.status());
            assertEquals(Ticket.Status.CONFIRMED, late.body().status());
            sys.awaitQuiescent(5000);
            assertEquals(1, sys.repository.all().size());
            assertEquals(1, sys.repository.count(ReservationStatus.CONFIRMED));
        }
    }

    @Test
    public void sameKeyFromDifferentUsersAreDifferentRequests() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1)).start()) {
            String a = TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "k", 0);
            String b = TestSupport.reserveAndWait(sys, "b", TestSupport.slot("R2", 9), "k", 0);
            assertEquals(2, sys.repository.count(ReservationStatus.CONFIRMED));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, a));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, b));
        }
    }
}
