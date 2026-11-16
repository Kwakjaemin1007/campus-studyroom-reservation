package com.studyroom.control.performance.api;

import com.studyroom.domain.SlotId;

/** 창구(Boundary)가 접수에 넘기는 요청 내용. */
public record SubmitCommand(String userId, int priority, SlotId slot, int bidAmount) {
}
