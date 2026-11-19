package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.FcfsPolicy;
import com.studyroom.control.modifiable.PriorityPolicy;
import com.studyroom.control.modifiable.api.PolicyLease;
import com.studyroom.control.modifiable.api.SwitchPhase;
import com.studyroom.control.modifiable.api.SwitchStatus;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.Ticket;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** QAS-2: 요청을 처리하는 중에 선착순을 크레딧 베팅으로 바꾼다. 안 멈추고, 안 섞이고, 유실이 없다. */
public class Qas2PolicySwitchTest {
    private static final String ADMIN = "admin:root";

    @Test
    public void switchUnderLoadDoesNotMixPoliciesOrLoseRequests() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1).withRates(1000, 100_000)).start()) {
            AtomicBoolean running = new AtomicBoolean(true);
            AtomicInteger submitted = new AtomicInteger();
            AtomicInteger notAccepted = new AtomicInteger();
            Thread load = new Thread(() -> {
                int n = 0;
                while (running.get()) {
                    // 슬롯마다 한 명씩: 선착순이면 바로 확정, 베팅이면 보류된다.
                    ApiResponse<Ticket> r = sys.studentApi.reserve("user:u" + n, TestSupport.slot("S" + n, 9), "k" + n, 10);
                    if (r.status() != 202) notAccepted.incrementAndGet();
                    submitted.incrementAndGet();
                    n++;
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            load.start();
            Thread.sleep(150);

            ApiResponse<String> res = sys.adminApi.switchPolicy(ADMIN, CreditBettingPolicy.NAME);
            assertEquals(202, res.status());
            long started = System.currentTimeMillis();
            TestSupport.await(() -> sys.policySwitch.status(res.body()).orElseThrow().state() == SwitchStatus.State.COMPLETED, 5000);
            long took = System.currentTimeMillis() - started;

            Thread.sleep(150);
            running.set(false);
            load.join();
            sys.awaitQuiescent(10_000);

            SwitchStatus status = sys.policySwitch.status(res.body()).orElseThrow();
            assertEquals(1, status.fromVersion());
            assertEquals(2, status.toVersion());
            assertEquals(CreditBettingPolicy.NAME, sys.policyRegistry.current().name());
            assertEquals(SwitchPhase.ACTIVE, sys.policySwitch.phase());
            assertFalse("전환 뒤 소비가 재개됐다", sys.processor.isPaused());
            assertTrue("유휴 후 5초 내 전환 (실제 " + took + "ms)", took < 5000);

            assertEquals("전환 중에도 접수는 전부 202", 0, notAccepted.get());
            assertEquals("유실 0", submitted.get(), sys.repository.all().size());

            int v1 = 0, v2 = 0;
            for (Reservation r : sys.repository.all()) {
                if (r.policyVersion() == 1) {
                    v1++;
                    assertEquals("버전 1은 선착순으로만 배정", FcfsPolicy.NAME, r.policyName());
                    assertEquals(ReservationStatus.CONFIRMED, r.status());
                } else {
                    v2++;
                    assertEquals(2, r.policyVersion());
                    assertEquals("버전 2는 베팅으로만 접수", CreditBettingPolicy.NAME, r.policyName());
                    assertEquals(ReservationStatus.REQUESTED, r.status());
                }
            }
            assertTrue("전환 전후 모두 요청이 있었다", v1 > 0 && v2 > 0);
        }
    }

    @Test
    public void onlyOneOfTwoConcurrentSwitchesWins() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1)).start()) {
            ApiResponse<String> first;
            // 배정 한 건이 진행 중인 것처럼 읽기 락을 잡아 두면 첫 전환이 쓰기 락에서 기다린다.
            try (PolicyLease inFlightAllocation = sys.policyRegistry.lease()) {
                assertEquals(FcfsPolicy.NAME, inFlightAllocation.handle().name());
                first = sys.adminApi.switchPolicy(ADMIN, PriorityPolicy.NAME);
                assertEquals(202, first.status());
                TestSupport.await(() -> sys.policySwitch.phase() == SwitchPhase.DRAINING, 5000);
                ApiResponse<String> second = sys.adminApi.switchPolicy(ADMIN, CreditBettingPolicy.NAME);
                assertEquals("동시에 누른 다른 관리자는 409", 409, second.status());
                assertEquals("진행 중인 배정이 있는 동안은 바뀌지 않는다", FcfsPolicy.NAME, sys.policyRegistry.current().name());
            }
            TestSupport.await(() -> sys.policySwitch.status(first.body()).orElseThrow().state() == SwitchStatus.State.COMPLETED, 5000);
            assertEquals(PriorityPolicy.NAME, sys.policyRegistry.current().name());
            assertEquals(2, sys.policyRegistry.current().version());
        }
    }

    @Test
    public void switchIsRefusedWhileBidsAreOpen() throws Exception {
        SystemConfig cfg = SystemConfig.defaults().withMinPartySize(1).withInitialPolicy(CreditBettingPolicy.NAME);
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), cfg).start()) {
            String id = TestSupport.reserveAndWait(sys, "a", TestSupport.slot("R1", 9), "k1", 20);
            assertEquals(ReservationStatus.REQUESTED, TestSupport.statusOf(sys, id));
            assertEquals(409, sys.adminApi.switchPolicy(ADMIN, FcfsPolicy.NAME).status());
            assertEquals(CreditBettingPolicy.NAME, sys.policyRegistry.current().name());
        }
    }

    @Test
    public void rejectsUnknownOrSamePolicyAndNonAdmins() {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1))) {
            assertEquals(400, sys.adminApi.switchPolicy(ADMIN, "LOTTERY").status());
            assertEquals(409, sys.adminApi.switchPolicy(ADMIN, FcfsPolicy.NAME).status());
            assertEquals(403, sys.adminApi.switchPolicy("user:a", PriorityPolicy.NAME).status());
            assertEquals(401, sys.adminApi.switchPolicy("garbage", PriorityPolicy.NAME).status());
            assertEquals(1, sys.adminApi.currentPolicy(ADMIN).body().version());
        }
    }

    @Test
    public void expiredLeaseRecoversAStuckSwitch() {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1))) {
            // 전환기가 DRAINING에서 죽은 상황을 만든다.
            assertTrue(sys.switchState.compareAndSet(SwitchPhase.ACTIVE, SwitchPhase.DRAINING));
            sys.switchState.setLeaseExpiry(Instant.now().minusSeconds(1));
            sys.processor.pause();

            assertTrue(sys.policySwitch.recoverIfLeaseExpired());
            assertEquals(SwitchPhase.ACTIVE, sys.policySwitch.phase());
            assertFalse(sys.processor.isPaused());
            assertEquals("정책은 바뀌지 않았다", FcfsPolicy.NAME, sys.policyRegistry.current().name());
        }
    }
}
