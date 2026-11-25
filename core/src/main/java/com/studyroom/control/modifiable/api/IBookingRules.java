package com.studyroom.control.modifiable.api;

import com.studyroom.domain.SlotId;

import java.time.Duration;
import java.time.Instant;

/**
 * 언제 어떤 자리를 신청할 수 있는가, 마감형 신청은 언제 판정하는가.
 * 운영 시간과 시험 기간 규칙이 여기 모여 있어서, 규칙이 바뀌어도 접수·배정 코드는 그대로다.
 */
public interface IBookingRules {
    /** 접수 전에 부른다. 받을 수 없으면 BookingRuleException */
    void check(SlotId slot, Instant now);

    /**
     * 마감형 정책에서 이 슬롯의 신청을 모아 판정할 시각.
     * 시험 기간의 내일 자리는 오늘 운영 종료 시각에 한꺼번에, 그 밖에는 지금부터 collectWindow 뒤(슬롯 시작 전까지).
     */
    Instant closeAt(SlotId slot, Instant now, Duration collectWindow);
}
