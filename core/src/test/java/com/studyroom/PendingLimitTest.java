package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.control.modifiable.FcfsPolicy;
import com.studyroom.control.performance.AllocationProcessor;
import com.studyroom.domain.PartyMember;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.domain.Ticket;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;

/**
 * 보유 한도 (11/26 결정): 한 사람은 시작 전 예약(접수 중·확정)을 3개까지만 들고 있을 수 있다.
 * 예약 가능 기간이 7일이라 "일주일 안에 3슬롯"과 같다. 신청자·동행 모두 각자 센다.
 */
public class PendingLimitTest {
    private static StudyRoomSystem system(MutableClock clock) {
        return new StudyRoomSystem(clock, SystemConfig.defaults().withInitialPolicy(FcfsPolicy.NAME).withRates(100, 5000)).start();
    }

    /** 12/2부터 n일 뒤 10시 자리 */
    private static SlotId day(int n) {
        return new SlotId("R1", TestSupport.slot("R1", 1).startsAt().plus(Duration.ofDays(n)));
    }

    private static String reserve(StudyRoomSystem sys, String user, SlotId slot, String key, String mate) throws Exception {
        ApiResponse<Ticket> res = sys.studentApi.reserve("user:" + user, slot, key, 0, List.of(new PartyMember(mate, 0)));
        assertEquals(res.error(), 202, res.status());
        sys.awaitQuiescent(5000);
        return res.body().ticketId();
    }

    private static String reasonOf(StudyRoomSystem sys, String id) {
        return sys.repository.findById(id).orElseThrow().reason();
    }

    @Test
    public void fourthPendingReservationIsRejected() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0))) {
            for (int i = 0; i < 3; i++) {
                assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, reserve(sys, "a", day(i), "k" + i, "m" + i)));
            }
            String fourth = reserve(sys, "a", day(3), "k3", "m3");
            assertEquals(ReservationStatus.REJECTED, TestSupport.statusOf(sys, fourth));
            assertEquals(AllocationProcessor.PENDING_LIMIT, reasonOf(sys, fourth));
        }
    }

    @Test
    public void companionSeatsCountToo() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0))) {
            reserve(sys, "a", day(0), "k0", "b");   // b는 동행
            reserve(sys, "c", day(1), "k1", "b");
            reserve(sys, "b", day(2), "k2", "d");   // b는 신청자
            String overByCompanion = reserve(sys, "e", day(3), "k3", "b");
            assertEquals("b가 이미 3개를 들고 있어 팀 전체가 거절된다", AllocationProcessor.PENDING_LIMIT, reasonOf(sys, overByCompanion));
        }
    }

    @Test
    public void cancelingOrUsingASeatFreesRoom() throws Exception {
        MutableClock clock = new MutableClock(TestSupport.T0);
        try (StudyRoomSystem sys = system(clock)) {
            String first = reserve(sys, "a", day(0), "k0", "m0");
            String second = reserve(sys, "a", day(1), "k1", "m1");
            reserve(sys, "a", day(2), "k2", "m2");

            assertEquals(200, sys.studentApi.cancel("user:a", second).status());
            assertEquals("직접 취소하면 한 자리가 빈다", ReservationStatus.CONFIRMED,
                    TestSupport.statusOf(sys, reserve(sys, "a", day(3), "k3", "m3")));

            // 첫 예약에 입실하면 더는 "시작 전 예약"이 아니라서 새로 잡을 수 있다.
            clock.set(day(0).startsAt());
            sys.qrDevice.onRawScan("RSV:" + first, clock.instant());
            assertEquals(ReservationStatus.CHECKED_IN, TestSupport.statusOf(sys, first));
            assertEquals(ReservationStatus.CONFIRMED, TestSupport.statusOf(sys, reserve(sys, "a", day(4), "k4", "m4")));
        }
    }

    @Test
    public void concurrentRequestsOnDifferentDaysStillStopAtThree() throws Exception {
        try (StudyRoomSystem sys = system(new MutableClock(TestSupport.T0))) {
            ExecutorService pool = Executors.newFixedThreadPool(7);
            List<Future<ApiResponse<Ticket>>> futures = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                final int n = i;
                futures.add(pool.submit(() -> sys.studentApi.reserve("user:a", day(n), "k" + n, 0, List.of(new PartyMember("m" + n, 0)))));
            }
            for (Future<ApiResponse<Ticket>> f : futures) assertEquals(202, f.get().status());
            pool.shutdown();
            sys.awaitQuiescent(10_000);
            assertEquals("날짜가 달라도 사람 락 안에서 세므로 3개를 넘지 않는다", 3, sys.repository.count(ReservationStatus.CONFIRMED));
            assertEquals(4, sys.repository.count(ReservationStatus.REJECTED));
        }
    }
}
