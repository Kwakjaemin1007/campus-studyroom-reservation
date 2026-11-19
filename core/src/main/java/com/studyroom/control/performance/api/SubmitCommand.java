package com.studyroom.control.performance.api;

import com.studyroom.domain.PartyMember;
import com.studyroom.domain.SlotId;

import java.util.List;

/**
 * 창구(Boundary)가 접수에 넘기는 요청 내용.
 *
 * @param priority   신청자 본인의 우선순위
 * @param bidAmount  1인당 입찰액. 구성원 전원이 이 금액을 똑같이 건다(크레딧 베팅이 아니면 0)
 * @param companions 함께 이용하는 사람. 창구가 재학생 조회를 마친 학번과 그 사람의 우선순위. 신청자는 넣지 않는다
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
