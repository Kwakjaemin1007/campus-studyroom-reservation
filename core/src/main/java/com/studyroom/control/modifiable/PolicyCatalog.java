package com.studyroom.control.modifiable;

import com.studyroom.control.modifiable.api.IAllocationPolicy;

import java.util.List;

/**
 * 배정 정책 목록. 새 정책은 IAllocationPolicy를 구현한 클래스 하나를 만들고 여기에 한 줄 더한다.
 * 메모리 조립(StudyRoomSystem)과 AWS 조립(AwsConfig)이 모두 이 목록을 쓰므로 다른 곳은 고치지 않는다.
 */
public final class PolicyCatalog {
    private PolicyCatalog() {
    }

    public static List<IAllocationPolicy> standard(CreditBettingPolicy.Pricing pricing) {
        return List.of(
                new FcfsPolicy(),
                new PriorityPolicy(),
                new CreditBettingPolicy(pricing));
    }
}
