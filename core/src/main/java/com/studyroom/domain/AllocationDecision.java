package com.studyroom.domain;

/** 정책이 요청 한 건에 내린 판정. charge는 낙찰 시 차감할 크레딧(베팅이 아니면 0). */
public record AllocationDecision(String requestId, Outcome outcome, String reason, int charge) {
    public enum Outcome { CONFIRM, REJECT }

    public static AllocationDecision confirm(String requestId, int charge) {
        return new AllocationDecision(requestId, Outcome.CONFIRM, null, charge);
    }

    public static AllocationDecision reject(String requestId, String reason) {
        return new AllocationDecision(requestId, Outcome.REJECT, reason, 0);
    }
}
