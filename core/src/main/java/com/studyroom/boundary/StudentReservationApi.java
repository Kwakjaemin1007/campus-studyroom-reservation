package com.studyroom.boundary;

import com.studyroom.domain.PartyMember;
import java.util.ArrayList;
import java.util.List;

import com.studyroom.control.availability.api.IReservationLifecycle;
import com.studyroom.control.modifiable.api.AuthenticationException;
import com.studyroom.control.modifiable.api.IAuthPort;
import com.studyroom.control.modifiable.api.StudentDirectoryUnavailableException;
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
    /** 400 오류 앞부분: 재학생이 아닌 학번. 뒤에 ":학번"이 붙어 화면이 어느 칸인지 표시한다. */
    public static final String NOT_ENROLLED = "NOT_ENROLLED";

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

    /** 동행 없이 혼자 신청(최소 인원이 1인 설정에서만 접수된다) */
    public ApiResponse<Ticket> reserve(String token, SlotId slot, String idempotencyKey, int bidAmount) {
        return reserve(token, slot, idempotencyKey, bidAmount, List.of());
    }

    /**
     * POST /reservations — 202 + 티켓. 결과는 getTicket으로 폴링하거나 푸시로 받는다.
     *
     * @param bidAmount  1인당 입찰액. 구성원 전원이 같은 금액을 걸고, 팀 입찰액은 1인당 × 인원이다(회의 결정 11/19)
     * @param companions 함께 이용하는 사람의 학번(신청자 제외). 각자의 bid 값은 쓰지 않는다.
     *                   신청자 포함 최소 인원은 설정값(2명)이고, 학번은 실제 재학생이어야 한다
     */
    public ApiResponse<Ticket> reserve(String token, SlotId slot, String idempotencyKey, int bidAmount,
                                       List<PartyMember> companions) {
        Identity who;
        try {
            who = auth.verify(token);
        } catch (AuthenticationException e) {
            return ApiResponse.error(401, e.getMessage());
        }
        if (!rateLimiter.tryAcquire(who.userId())) return ApiResponse.tooManyRequests(1);
        List<PartyMember> verified = new ArrayList<>(companions.size());
        try {
            for (PartyMember m : companions) {
                // 본인 학번이나 중복 학번은 접수(ReservationIntake)가 400으로 거절한다. 여기서는 재학생인지만 본다.
                Optional<Identity> student = m.userId().equals(who.userId()) ? Optional.of(who) : auth.lookupStudent(m.userId());
                if (student.isEmpty()) return ApiResponse.error(400, NOT_ENROLLED + ":" + m.userId());
                verified.add(new PartyMember(m.userId(), bidAmount, student.get().priority()));
            }
        } catch (StudentDirectoryUnavailableException e) {
            return ApiResponse.error(503, "학사 시스템에서 학번을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요");
        }
        try {
            Ticket ticket = intake.submit(new SubmitCommand(who.userId(), who.priority(), slot, bidAmount, verified), idempotencyKey);
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
