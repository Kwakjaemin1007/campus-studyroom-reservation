package com.studyroom.control.performance.api;

/** 정책 전환 때 PolicySwitchManager가 부르는 문. pause 동안에도 접수는 계속되고 요청은 큐에 쌓인다. */
public interface IAllocationGate {
    void pause();

    void resume();

    /** 지금 배정 처리 중인 메시지 수. */
    int inFlightCount();
}
