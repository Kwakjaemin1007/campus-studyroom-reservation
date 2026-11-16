package com.studyroom.control.modifiable.api;

/** "지금 어떤 정책이 끼워져 있나"에 답한다. */
public interface IPolicyProvider {
    /** 조회용. 락을 잡지 않는다. */
    PolicyHandle current();

    /** 배정용. 반환된 임대를 닫을 때까지 정책이 바뀌지 않는다. */
    PolicyLease lease();
}
