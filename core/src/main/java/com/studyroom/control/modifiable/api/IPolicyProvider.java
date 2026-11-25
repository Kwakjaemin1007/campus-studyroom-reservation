package com.studyroom.control.modifiable.api;

import com.studyroom.domain.SlotId;

/**
 * "어떤 정책이 끼워져 있나"에 답한다.
 * 평소 정책은 관리자가 전환하고, 시험 기간 날짜는 달력에 등록한 정책이 덮어쓴다(자리 날짜 기준).
 * 버전은 하나다. 평소 정책 전환과 시험 기간 등록·취소 모두 버전을 올린다.
 */
public interface IPolicyProvider {
    /** 평소 정책. 조회용이라 락을 잡지 않는다. */
    PolicyHandle current();

    /** 그 자리의 날짜에 적용되는 정책. 조회용이라 락을 잡지 않는다. */
    PolicyHandle current(SlotId slot);

    /** 평소 정책을 빌린다. 반환된 임대를 닫을 때까지 정책이 바뀌지 않는다. */
    PolicyLease lease();

    /** 배정용. 그 자리의 날짜에 적용되는 정책을 빌린다. 닫을 때까지 정책도 달력도 바뀌지 않는다. */
    PolicyLease lease(SlotId slot);

    /** 등록된 정책을 이름으로 찾는다(화면에 시험 기간 정책의 규칙을 알려 줄 때). 없으면 UnknownPolicyException */
    IAllocationPolicy byName(String name);
}
