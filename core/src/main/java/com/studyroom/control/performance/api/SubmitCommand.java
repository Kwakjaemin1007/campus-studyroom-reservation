package com.studyroom.control.performance.api;

import com.studyroom.domain.PartyMember;
import com.studyroom.domain.SlotId;

import java.util.List;

/**
 * 창구(Boundary)가 접수에 넘기는 요청 내용.
 *
 * @param bidAmount  신청자 본인이 거는 크레딧(크레딧 베팅이 아니면 0)
 * @param companions 함께 이용하는 사람(학번)과 각자 거는 크레딧. 신청자는 넣지 않는다
 */
public record SubmitCommand(String userId, int priority, SlotId slot, int bidAmount, List<PartyMember> companions) {
    public SubmitCommand {
        companions = companions == null ? List.of() : List.copyOf(companions);
    }

    /** 동행 없이 혼자 신청(최소 인원이 1일 때만 접수된다) */
    public SubmitCommand(String userId, int priority, SlotId slot, int bidAmount) {
        this(userId, priority, slot, bidAmount, List.of());
    }
}
