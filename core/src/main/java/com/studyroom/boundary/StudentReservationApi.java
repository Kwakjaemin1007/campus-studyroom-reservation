package com.studyroom.boundary;

import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.modifiable.api.AuthenticationException;
import com.studyroom.control.modifiable.api.IAuthPort;
import com.studyroom.control.performance.api.IReservationIntake;
import com.studyroom.control.performance.api.QueueUnavailableException;
import com.studyroom.control.performance.api.SlotClosedException;
import com.studyroom.control.performance.api.SubmitCommand;
import com.studyroom.domain.Identity;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.SlotId;
import com.studyroom.domain.Ticket;

import java.util.Optional;

/**
 * 학생용 창구 (Boundary). 유입 제한, 인증, 오류 코드 변환만 하고 판단은 Control에 맡긴다.
 * 인증만은 횡단 관심사라 IAuthPort로 Adapter를 직접 부른다(계층 규칙의 유일한 예외).
 */
public final class StudentReservationApi {
    private final IAuthPort auth;
    private final RateLimiter rateLimiter;
    private final IReservationIntake intake;
    private final IReservationLifecycle lifecycle;

    public StudentReservationApi(IAuthPort auth, RateLimiter rateLimiter,
                                 IReservationIntake intake, IReservationLifecycle lifecycle) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.intake = intake;
        this.lifecycle = lifecycle;
    }

    /** POST /reservations — 202 + 티켓. 결과는 getTicket으로 폴링하거나 푸시로 받는다. */
    public ApiResponse<Ticket> reserve(String token, SlotId slot, String idempotencyKey, int bidAmount) {
        Identity who;
        try {
            who = auth.verify(token);
        } catch (AuthenticationException e) {
            return ApiResponse.error(401, e.getMessage());
        }
        if (!rateLimiter.tryAcquire(who.userId())) return ApiResponse.tooManyRequests(1);
        try {
            Ticket ticket = intake.submit(new SubmitCommand(who.userId(), who.priority(), slot, bidAmount), idempotencyKey);
            return ApiResponse.accepted(ticket);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (SlotClosedException e) {
            return ApiResponse.error(409, "이미 마감된 슬롯입니다");
        } catch (QueueUnavailableException e) {
            return ApiResponse.error(503, "잠시 후 다시 시도해 주세요");
        }
    }

    /** GET /tickets/{id} */
    public ApiResponse<Ticket> getTicket(String token, String ticketId) {
        try {
            auth.verify(token);
        } catch (AuthenticationException e) {
            return ApiResponse.error(401, e.getMessage());
        }
        return ApiResponse.ok(intake.getTicket(ticketId));
    }

    /** DELETE /reservations/{id} — 본인 예약만 취소할 수 있다. */
    public ApiResponse<Void> cancel(String token, String reservationId) {
        Identity who;
        try {
            who = auth.verify(token);
        } catch (AuthenticationException e) {
            return ApiResponse.error(401, e.getMessage());
        }
        Optional<Reservation> r = lifecycle.find(reservationId);
        if (r.isEmpty()) return ApiResponse.error(404, "예약이 없습니다");
        if (!r.get().userId().equals(who.userId())) return ApiResponse.error(403, "본인 예약이 아닙니다");
        return switch (lifecycle.cancel(reservationId, "USER_CANCELED")) {
            case APPLIED -> ApiResponse.ok(null);
            case NOT_FOUND -> ApiResponse.error(404, "예약이 없습니다");
            case NO_OP -> ApiResponse.error(409, "취소할 수 없는 상태입니다");
        };
    }
}
