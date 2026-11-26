package com.studyroom.control.modifiable;

import com.studyroom.domain.ReservationRequest;

import java.util.Comparator;
import java.util.function.ToIntFunction;

/**
 * 마감형 정책(크레딧 베팅)의 팀 순위 규칙(회의 결정 11/19).
 *  1. 팀 합계(입찰액 합)가 큰 팀
 *  2. 같으면 팀 패널티(구성원의 이번 학기 노쇼 횟수 합)가 적은 팀
 *  3. 그래도 같으면 인원이 많은 팀
 *  4. 그래도 같으면 먼저 접수된 팀
 */
final class TeamRanking {
    private TeamRanking() {
    }

    /** 앞에 올수록 우선인 비교기 */
    static Comparator<ReservationRequest> by(ToIntFunction<ReservationRequest> teamTotal) {
        return Comparator.comparingInt(teamTotal).reversed()
                .thenComparingInt(ReservationRequest::teamPenalty)
                .thenComparing(Comparator.comparingInt(ReservationRequest::partySize).reversed())
                .thenComparingLong(ReservationRequest::sequence);
    }
}
