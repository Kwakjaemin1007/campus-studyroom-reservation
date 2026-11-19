package com.studyroom.demo;

import com.studyroom.domain.PartyMember;

import com.studyroom.StudyRoomSystem;
import com.studyroom.SystemConfig;
import com.studyroom.boundary.ApiResponse;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.api.SwitchStatus;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.domain.Ticket;
import com.studyroom.support.MutableClock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 품질 시나리오(QAS) 네 개를 메모리 구현 위에서 실제로 돌려 보고 결과를 출력한다.
 * 지연 시간은 메모리 구현의 값이라 AWS에서의 수치와는 다르다. 여기서 확인하는 것은
 * "이중 예약 0, 유실 0, 섞인 배정 0" 같은 정합성 기준이다.
 */
public final class QasDemo {
    private static final Instant DAY = Instant.parse("2026-12-01T00:00:00Z");

    public static void main(String[] args) throws Exception {
        qas1();
        qas2();
        qas3();
        qas5();
    }

    private static SlotId slot(String room) {
        return new SlotId(room, Instant.now().plus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.HOURS));
    }

    private static void qas1() throws Exception {
        System.out.println("== QAS-1 성능: 1초 안에 1,000건, 슬롯 40개, 선착순 ==");
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults()).start()) {
            int users = 1000, slots = 40;
            ExecutorService pool = Executors.newFixedThreadPool(64);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<long[]>> futures = new ArrayList<>();
            for (int i = 0; i < users; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    go.await();
                    long t0 = System.nanoTime();
                    ApiResponse<Ticket> r = sys.studentApi.reserve("user:s" + n, slot("R" + (n % slots)), "key-" + n, 0, List.of(new PartyMember("s-mate" + n, 0)));
                    return new long[]{r.status(), System.nanoTime() - t0};
                }));
            }
            long start = System.nanoTime();
            go.countDown();
            List<Long> latencies = new ArrayList<>();
            Map<Long, Integer> byStatus = new HashMap<>();
            for (Future<long[]> f : futures) {
                long[] r = f.get();
                byStatus.merge(r[0], 1, Integer::sum);
                latencies.add(r[1]);
            }
            pool.shutdown();
            sys.awaitQuiescent(30_000);
            long totalMs = (System.nanoTime() - start) / 1_000_000;
            Collections.sort(latencies);
            long confirmed = sys.repository.count(ReservationStatus.CONFIRMED);
            long rejected = sys.repository.count(ReservationStatus.REJECTED);
            int early = byStatus.getOrDefault(409L, 0);
            System.out.printf("  응답 코드별: %s%n", byStatus);
            System.out.printf("  확정 %d, 거절 %d(배정 후) + %d(조기 거절 409) → 합계 %d / %d%n",
                    confirmed, rejected, early, confirmed + rejected + early, users);
            System.out.printf("  이중 예약된 슬롯: %d%n", doubleBooked(sys.repository.all()));
            System.out.printf("  접수 p95: %.2fms, 1,000건 전부 결과가 나오기까지: %dms (메모리 구현 기준)%n",
                    latencies.get((int) (latencies.size() * 0.95)) / 1e6, totalMs);
        }
    }

    private static void qas2() throws Exception {
        System.out.println("== QAS-2 변경 용이성: 처리 중에 선착순 → 크레딧 베팅 ==");
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(),
                SystemConfig.defaults().withRates(1000, 100_000)).start()) {
            AtomicBoolean running = new AtomicBoolean(true);
            AtomicInteger submitted = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();
            Thread load = new Thread(() -> {
                int n = 0;
                while (running.get()) {
                    ApiResponse<Ticket> r = sys.studentApi.reserve("user:u" + n, slot("S" + n), "k" + n, 10, List.of(new PartyMember("u-mate" + n, 0)));
                    if (r.status() != 202) refused.incrementAndGet();
                    submitted.incrementAndGet();
                    n++;
                    try {
                        Thread.sleep(20);   // 초당 약 50건
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            load.start();
            Thread.sleep(1000);
            long t0 = System.nanoTime();
            String switchId = sys.adminApi.switchPolicy("admin:root", CreditBettingPolicy.NAME).body();
            while (sys.policySwitch.status(switchId).orElseThrow().state() != SwitchStatus.State.COMPLETED) Thread.sleep(1);
            long switchMs = (System.nanoTime() - t0) / 1_000_000;
            Thread.sleep(1000);
            running.set(false);
            load.join();
            sys.awaitQuiescent(10_000);

            int mixed = 0, v1 = 0, v2 = 0;
            for (Reservation r : sys.repository.all()) {
                boolean ok = (r.policyVersion() == 1 && r.policyName().equals("FCFS"))
                        || (r.policyVersion() == 2 && r.policyName().equals(CreditBettingPolicy.NAME));
                if (!ok) mixed++;
                if (r.policyVersion() == 1) v1++;
                else v2++;
            }
            System.out.printf("  전환 소요: %dms (요청부터 완료까지), 재시작 0%n", switchMs);
            System.out.printf("  접수 %d건 중 거절된 접수 %d건, 기록된 요청 %d건 → 유실 %d%n",
                    submitted.get(), refused.get(), sys.repository.all().size(), submitted.get() - sys.repository.all().size());
            System.out.printf("  버전 1(선착순) %d건, 버전 2(베팅) %d건, 두 정책이 섞인 배정 %d건%n", v1, v2, mixed);
        }
    }

    private static void qas3() throws Exception {
        System.out.println("== QAS-3 가용성: 미체크인 예약 40건의 유예 만료 ==");
        MutableClock clock = new MutableClock(DAY);
        try (StudyRoomSystem sys = new StudyRoomSystem(clock, SystemConfig.defaults()).start()) {
            Instant startsAt = DAY.plus(Duration.ofHours(33));
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                ids.add(sys.studentApi.reserve("user:n" + i, new SlotId("N" + i, startsAt), "k" + i, 0, List.of(new PartyMember("n-mate" + i, 0))).body().ticketId());
            }
            sys.awaitQuiescent(10_000);
            for (int i = 0; i < 10; i++) sys.qrDevice.onRawScan("RSV:" + ids.get(i), startsAt);   // 10명은 입실
            long updatesBefore = sys.repository.conditionalUpdateCount();

            clock.set(startsAt.plus(Duration.ofMinutes(10)));
            int delivered = sys.scheduler.tick();
            for (int i = 0; i < 40; i++) sys.noShowHandler.onGraceExpired(ids.get(i));   // 같은 메시지가 한 번 더 온다

            System.out.printf("  전달된 만료 메시지 %d건(입실한 10건은 타이머가 지워짐)%n", delivered);
            System.out.printf("  노쇼 해제 %d건, 무시된 메시지 %d건(이미 입실했거나 중복)%n",
                    sys.noShowHandler.releasedCount(), sys.noShowHandler.ignoredCount());
            System.out.printf("  해제에 쓴 조건부 UPDATE %d회 → 건당 1회, 풀 스캔 0%n",
                    sys.repository.conditionalUpdateCount() - updatesBefore);
            System.out.printf("  최종 상태: 입실 %d, 노쇼 취소 %d%n",
                    sys.repository.count(ReservationStatus.CHECKED_IN), sys.repository.count(ReservationStatus.CANCELED));
        }
    }

    private static void qas5() throws Exception {
        System.out.println("== QAS-5 가용성: 피크 중 Redis(캐시) 중단 ==");
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults()).start()) {
            sys.slotCache.setAvailable(false);
            ExecutorService pool = Executors.newFixedThreadPool(32);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                final int n = i;
                futures.add(pool.submit(() -> sys.studentApi.reserve("user:c" + n, slot("C" + (n % 40)), "k" + n, 0, List.of(new PartyMember("c-mate" + n, 0))).status()));
            }
            int errors = 0;
            for (Future<Integer> f : futures) if (f.get() >= 500) errors++;
            pool.shutdown();
            sys.awaitQuiescent(30_000);
            System.out.printf("  5xx %d건, 확정 %d, 거절 %d, 이중 예약된 슬롯 %d%n", errors,
                    sys.repository.count(ReservationStatus.CONFIRMED), sys.repository.count(ReservationStatus.REJECTED),
                    doubleBooked(sys.repository.all()));
            sys.slotCache.setAvailable(true);
            long t0 = System.nanoTime();
            sys.rebuildSlotCache();
            System.out.printf("  복구 후 캐시 재구성: 슬롯 %d개, %.2fms%n", sys.slotCache.size(), (System.nanoTime() - t0) / 1e6);
        }
    }

    private static long doubleBooked(List<Reservation> all) {
        Map<String, Integer> perSlot = new HashMap<>();
        for (Reservation r : all) if (r.status().occupiesSlot()) perSlot.merge(r.slot().key(), 1, Integer::sum);
        return perSlot.values().stream().filter(n -> n > 1).count();
    }
}
