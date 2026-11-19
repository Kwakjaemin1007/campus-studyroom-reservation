package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.Ticket;
import org.junit.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** QAS-1: 1,000명이 슬롯 40개에 한꺼번에 요청한다. 이중 예약 0, 유실 0, 5xx 0. */
public class Qas1PeakLoadTest {
    @Test
    public void thousandRequestsOnFortySlots() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1)).start()) {
            int users = 1000, slots = 40;
            ExecutorService pool = Executors.newFixedThreadPool(64);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ApiResponse<Ticket>>> futures = new ArrayList<>();
            for (int i = 0; i < users; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return sys.studentApi.reserve("user:s" + n, TestSupport.slot("R" + (n % slots), 9), "key-" + n, 0);
                }));
            }
            go.countDown();
            int accepted = 0, earlyRejected = 0, serverErrors = 0;
            List<String> tickets = new ArrayList<>();
            for (Future<ApiResponse<Ticket>> f : futures) {
                ApiResponse<Ticket> r = f.get(10, TimeUnit.SECONDS);
                if (r.status() == 202) {
                    accepted++;
                    tickets.add(r.body().ticketId());
                } else if (r.status() == 409) earlyRejected++;
                else if (r.status() >= 500) serverErrors++;
            }
            pool.shutdown();
            sys.awaitQuiescent(10_000);

            long confirmed = sys.repository.count(ReservationStatus.CONFIRMED);
            long rejected = sys.repository.count(ReservationStatus.REJECTED);
            assertEquals("5xx", 0, serverErrors);
            assertEquals("접수 + 조기 거절 = 전체", users, accepted + earlyRejected);
            assertEquals("슬롯당 1건만 확정", slots, confirmed);
            assertEquals("유실 0: 확정 + 거절 = 1,000", users, confirmed + rejected + earlyRejected);
            assertEquals("이중 예약 0", 0, TestSupport.doubleBookedSlots(sys.repository.all()));
            for (String t : tickets) {
                assertNotEquals("모든 티켓에 결과가 있다", Ticket.Status.PENDING, sys.intake.getTicket(t).status());
            }
            assertTrue(sys.queue.deadLetters().isEmpty());
        }
    }

    @Test
    public void hotSlotGetsExactlyOneWinnerAndEarlyRejectsLateComers() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1)).start()) {
            ExecutorService pool = Executors.newFixedThreadPool(32);
            List<Future<ApiResponse<Ticket>>> futures = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                final int n = i;
                futures.add(pool.submit(() -> sys.studentApi.reserve("user:h" + n, TestSupport.slot("HOT", 9), "k" + n, 0)));
            }
            for (Future<ApiResponse<Ticket>> f : futures) f.get(10, TimeUnit.SECONDS);
            pool.shutdown();
            sys.awaitQuiescent(10_000);
            assertEquals(1, sys.repository.count(ReservationStatus.CONFIRMED));
            // 마감이 캐시에 기록된 뒤의 요청은 줄에 서지 않고 409를 받는다.
            assertEquals(409, sys.studentApi.reserve("user:late", TestSupport.slot("HOT", 9), "late", 0).status());
        }
    }
}
