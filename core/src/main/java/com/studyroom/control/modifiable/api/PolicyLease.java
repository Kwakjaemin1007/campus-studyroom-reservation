package com.studyroom.control.modifiable.api;

/**
 * 배정 한 건이 진행되는 동안 정책을 붙잡아 두는 임대(읽기 락).
 * 임대가 하나라도 열려 있으면 정책 전환(쓰기 락)은 기다린다. try-with-resources로 쓴다.
 */
public interface PolicyLease extends AutoCloseable {
    PolicyHandle handle();

    @Override
    void close();
}
