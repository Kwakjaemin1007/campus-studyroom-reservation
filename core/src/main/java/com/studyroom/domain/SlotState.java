package com.studyroom.domain;

/** 정책 판단에 넘기는 슬롯의 현재 상태. 정책은 I/O를 하지 않으므로 필요한 값은 여기 담아 준다. */
public record SlotState(SlotId slot, boolean taken) {
}
