package com.studyroom;

import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.FcfsPolicy;
import com.studyroom.control.modifiable.api.OperatingHours;

import java.time.Duration;

/**
 * 조립에 쓰는 설정값. 팀이 아직 정하지 않은 값(★)은 임시 기본값이다.
 *
 * @param workers          배정 워커 수 (지연 예산 검산의 16개)
 * @param grace            노쇼 유예 (슬롯 시작 + 10분)
 * @param collectWindow    ★ 베팅·우선순위 접수 창 길이
 * @param initialCredits   ★ 학기당 크레딧 지급량
 * @param pricing          ★ 낙찰가 방식
 * @param perUserRate      ★ 사용자별 초당 요청 한도
 * @param globalRate       ★ 전역 초당 요청 한도 (피크 1,000건/초보다 높게)
 * @param maxReceiveCount  ★ DLQ로 보내기 전 최대 수신 횟수
 * @param minPartySize     신청자 포함 최소 인원 (11/19 회의: 2명)
 * @param hours            운영 시간 (11/25 결정: 09:00~22:00). 시험 기간 내일 자리 입찰 마감도 close 시각
 */
public record SystemConfig(int workers, Duration lockLease, Duration grace, Duration collectWindow,
                           int initialCredits, CreditBettingPolicy.Pricing pricing,
                           int perUserRate, int globalRate, int maxReceiveCount,
                           Duration switchIdleWait, Duration switchLease, String initialPolicy,
                           int minPartySize, OperatingHours hours) {

    public SystemConfig(int workers, Duration lockLease, Duration grace, Duration collectWindow,
                        int initialCredits, CreditBettingPolicy.Pricing pricing,
                        int perUserRate, int globalRate, int maxReceiveCount,
                        Duration switchIdleWait, Duration switchLease, String initialPolicy, int minPartySize) {
        this(workers, lockLease, grace, collectWindow, initialCredits, pricing, perUserRate, globalRate, maxReceiveCount,
                switchIdleWait, switchLease, initialPolicy, minPartySize, OperatingHours.DEFAULT);
    }

    public static SystemConfig defaults() {
        return new SystemConfig(16, Duration.ofSeconds(5), Duration.ofMinutes(10), Duration.ofMinutes(10),
                100, CreditBettingPolicy.Pricing.FIRST_PRICE,
                5, 5000, 3,
                Duration.ofSeconds(2), Duration.ofSeconds(30), FcfsPolicy.NAME, 2);
    }

    public SystemConfig withInitialPolicy(String name) {
        return new SystemConfig(workers, lockLease, grace, collectWindow, initialCredits, pricing,
                perUserRate, globalRate, maxReceiveCount, switchIdleWait, switchLease, name, minPartySize, hours);
    }

    public SystemConfig withPricing(CreditBettingPolicy.Pricing p) {
        return new SystemConfig(workers, lockLease, grace, collectWindow, initialCredits, p,
                perUserRate, globalRate, maxReceiveCount, switchIdleWait, switchLease, initialPolicy, minPartySize, hours);
    }

    public SystemConfig withRates(int perUser, int global) {
        return new SystemConfig(workers, lockLease, grace, collectWindow, initialCredits, pricing,
                perUser, global, maxReceiveCount, switchIdleWait, switchLease, initialPolicy, minPartySize, hours);
    }

    public SystemConfig withWorkers(int n) {
        return new SystemConfig(n, lockLease, grace, collectWindow, initialCredits, pricing,
                perUserRate, globalRate, maxReceiveCount, switchIdleWait, switchLease, initialPolicy, minPartySize, hours);
    }

    public SystemConfig withHours(OperatingHours h) {
        return new SystemConfig(workers, lockLease, grace, collectWindow, initialCredits, pricing,
                perUserRate, globalRate, maxReceiveCount, switchIdleWait, switchLease, initialPolicy, minPartySize, h);
    }

    public SystemConfig withMinPartySize(int n) {
        return new SystemConfig(workers, lockLease, grace, collectWindow, initialCredits, pricing,
                perUserRate, globalRate, maxReceiveCount, switchIdleWait, switchLease, initialPolicy, n, hours);
    }
}
