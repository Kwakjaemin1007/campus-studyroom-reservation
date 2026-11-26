package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.FcfsPolicy;
import com.studyroom.control.modifiable.api.SwitchStatus;
import com.studyroom.control.performance.AllocationProcessor;
import com.studyroom.control.performance.api.PolicyChangedException;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.Ticket;
import org.junit.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 신청 시점의 규칙과 판정 시점의 규칙이 어긋나지 않는다(QAS-2 보강).
 * 선착순으로 낸 신청은 입찰이 없으므로 베팅 규칙으로 판정하면 안 된다.
 */
public class PolicyVersionGuardTest {
    private static final String ADMIN = "admin:root";

    @Test
    public void queuedRequestFromOldPolicyIsReturnedNotJudgedByNewRule() throws Exception {
        // 워커를 아직 띄우지 않아 신청을 큐에 남겨 둔 채(선착순, 버전 1) 베팅으로 바꾼다.
        try (StudyRoomSystem sys = new StudyRoomSystem(TestSupport.liveClock(), SystemConfig.defaults().withMinPartySize(1))) {
            ApiResponse<Ticket> r = sys.studentApi.reserve("user:a", TestSupport.slot("R1", 9), "k1", 0);
            assertEquals(202, r.status());
            ApiResponse<String> sw = sys.adminApi.switchPolicy(ADMIN, CreditBettingPolicy.NAME);
            TestSupport.await(() -> sys.policySwitch.status(sw.body()).orElseThrow().state() == SwitchStatus.State.COMPLETED, 5000);
            sys.start();
            sys.awaitQuiescent(5000);

            Reservation res = sys.repository.findById(r.body().ticketId()).orElseThrow();
            assertEquals(ReservationStatus.REJECTED, res.status());
            assertEquals("NO_BID가 아니라 정책 변경으로 돌려보낸다", AllocationProcessor.POLICY_CHANGED, res.reason());
            assertEquals(100, sys.credits.balance("a"));
        }
    }

    @Test
    public void staleAppVersionGets409AndCurrentVersionIsAccepted() {
        try (StudyRoomSystem sys = new StudyRoomSystem(TestSupport.liveClock(), SystemConfig.defaults().withMinPartySize(1)).start()) {
            ApiResponse<Ticket> stale = sys.studentApi.reserve("user:a", TestSupport.slot("R1", 9), "k1", 0, List.of(), 99);
            assertEquals(409, stale.status());
            assertTrue(stale.error().startsWith(PolicyChangedException.POLICY_CHANGED + ":"));
            int now = sys.policyRegistry.current().version();
            assertEquals(202, sys.studentApi.reserve("user:a", TestSupport.slot("R1", 9), "k2", 0, List.of(), now).status());
        }
    }

    @Test
    public void bidIsDroppedWhenPolicyDoesNotUseIt() throws Exception {
        SystemConfig cfg = SystemConfig.defaults().withMinPartySize(1).withInitialPolicy(FcfsPolicy.NAME);
        try (StudyRoomSystem sys = new StudyRoomSystem(TestSupport.liveClock(), cfg).start()) {
            String id = TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "k1", 30);
            Reservation res = sys.repository.findById(id).orElseThrow();
            assertEquals(ReservationStatus.CONFIRMED, res.status());
            assertEquals("선착순 정책은 입찰을 쓰지 않는다", 0, res.bidAmount());
            assertEquals("크레딧이 묶이지 않는다", 0, sys.credits.held("a"));
            assertEquals(100, sys.credits.balance("a"));
        }
    }

    @Test
    public void bettingStillHoldsCredits() throws Exception {
        SystemConfig cfg = SystemConfig.defaults().withMinPartySize(1).withInitialPolicy(CreditBettingPolicy.NAME);
        try (StudyRoomSystem sys = new StudyRoomSystem(TestSupport.liveClock(), cfg).start()) {
            TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "k1", 30);
            assertEquals(30, sys.credits.held("a"));
        }
    }
}
