package com.studyroom.control.performance;

import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.performance.api.IDeadLetterListener;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.SlotId;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DLQ로 간 메시지를 정리한다 (Dead Letter Channel).
 * 끝내 처리하지 못한 신청을 "처리 실패"로 거절해, 학생이 "처리 중"에 갇히거나 크레딧이 묶이지 않게 한다.
 * 거절은 IReservationLifecycle.reject가 하고, 그 안에서 크레딧 환불과 구성원 전원 알림까지 한다.
 * reject는 조건부 UPDATE(REQUESTED → REJECTED)라서 같은 메시지가 두 번 와도 환불·알림은 한 번이다.
 * 이미 확정된 신청이면 상태 전이가 막혀 아무 일도 일어나지 않는다.
 * 예외는 삼키지 않는다. 던지면 DLQ에 남아 다시 전달된다(DB 장애 등).
 */
public final class DeadLetterHandler implements IDeadLetterListener {
    public static final String PROCESSING_FAILED = "PROCESSING_FAILED";

    private final IReservationLifecycle lifecycle;
    private final AtomicLong handled = new AtomicLong();

    public DeadLetterHandler(IReservationLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** 접수 큐 DLQ: 배정을 끝내 못 한 신청. */
    @Override
    public void onDeadLetter(ReservationRequest request) {
        reject(request);
        handled.incrementAndGet();
    }

    /** timer DLQ의 SLOT_CLOSE: 마감 판정을 끝내 못 한 자리. 그 자리에 보류된 신청을 모두 거절·환불한다. */
    public void onSlotCloseFailed(String slotKey) {
        for (ReservationRequest q : lifecycle.heldRequests(SlotId.parse(slotKey))) {
            reject(q);
        }
        handled.incrementAndGet();
    }

    private void reject(ReservationRequest q) {
        lifecycle.reject(q, PROCESSING_FAILED, q.policyVersion(), q.policyName());
    }

    /** 처리한 DLQ 메시지 수(운영 확인용). */
    public long handledCount() {
        return handled.get();
    }
}
