package com.studyroom.control.modifiable.api;

import java.util.Optional;

/** 관리자의 정책 교체 창구. 요청은 바로 돌아오고, 실제 전환은 유휴 시점에 일어난다. */
public interface IPolicySwitch {
    /** @throws SwitchConflictException 다른 전환이 진행 중이거나 접수 창이 열려 있을 때 */
    String requestSwitch(String target);

    Optional<SwitchStatus> status(String switchId);

    /** 전환 상태 기계의 현재 위치. 화면이 "정책 전환 중"을 알리는 데 쓴다. */
    SwitchPhase phase();
}
