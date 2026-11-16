package com.studyroom.control.modifiable.api;

/** 현재 정책의 이름과 버전을 보관한다. 인스턴스가 여러 대면 공유 저장소(Redis)에 둔다. */
public interface IActivePolicyStore {
    ActivePolicy get();

    void set(ActivePolicy active);
}
