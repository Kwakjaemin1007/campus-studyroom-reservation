package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.control.availability.api.IReservationLifecycle.ConfirmResult;
import com.studyroom.domain.AllocationDecision;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.domain.Ticket;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** QAS-5: 피크 중 Redis가 죽어도 접수는 계속되고 이중 예약은 0이다. */
public class Qas5CacheFailureTest {
    @Test
    public void peakWithCacheDownStillBooksEachSlotOnce() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1)).start()) {
            sys.slotCache.setAvailable(false);

            ExecutorService pool = Executors.newFixedThreadPool(32);
            List<Future<ApiResponse<Ticket>>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                final int n = i;
                futures.add(pool.submit(() -> sys.studentApi.reserve("user:c" + n, TestSupport.slot("R" + (n % 10), 9), "k" + n, 0)));
            }
            int serverErrors = 0;
            for (Future<ApiResponse<Ticket>> f : futures) {
                if (f.get(10, TimeUnit.SECONDS).status() >= 500) serverErrors++;
            }
            pool.shutdown();
            sys.awaitQuiescent(10_000);

            assertEquals("5xx 0", 0, serverErrors);
            assertEquals(10, sys.repository.count(ReservationStatus.CONFIRMED));
            assertEquals(190, sys.repository.count(ReservationStatus.REJECTED));
            assertEquals("이중 예약 0", 0, TestSupport.doubleBookedSlots(sys.repository.all()));
            assertTrue("조기 거절을 건너뛰고 접수했다", sys.intake.degradedCount() >= 200);

            // 복구: 캐시를 DB에서 다시 만들면 조기 거절이 돌아온다.
            sys.slotCache.setAvailable(true);
            sys.rebuildSlotCache();
            assertEquals(10, sys.slotCache.size());
            assertEquals(409, sys.studentApi.reserve("user:late", TestSupport.slot("R0", 9), "late", 0).status());
        }
    }

    @Test
    public void databaseUniqueConstraintIsTheLastLineOfDefense() {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1))) {
            // 큐와 락을 모두 우회해 같은 슬롯에 두 건을 직접 확정해 본다.
            SlotId slot = TestSupport.slot("R1", 9);
            ReservationRequest a = new ReservationRequest("A", "ka", "a", slot, Instant.now(), 1, 0, 0);
            ReservationRequest b = new ReservationRequest("B", "kb", "b", slot, Instant.now(), 2, 0, 0);
            assertEquals(ConfirmResult.CONFIRMED, sys.lifecycle.confirm(a, AllocationDecision.confirm("A", 0), 1, "FCFS"));
            assertEquals(ConfirmResult.SLOT_TAKEN, sys.lifecycle.confirm(b, AllocationDecision.confirm("B", 0), 1, "FCFS"));
            assertEquals(ConfirmResult.DUPLICATE, sys.lifecycle.confirm(a, AllocationDecision.confirm("A", 0), 1, "FCFS"));
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, "B"));
            assertEquals(1, sys.repository.count(ReservationStatus.CONFIRMED));
        }
    }
}
