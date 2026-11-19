package com.studyroom.app.web;

import com.studyroom.domain.PartyMember;
import java.util.List;

import com.studyroom.boundary.StudentReservationApi;
import com.studyroom.domain.SlotId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** 학생용 HTTP 엔드포인트. 판단은 전부 StudentReservationApi(Boundary)에 맡긴다. */
@RestController
public class ReservationController {
    /**
     * @param bidAmount  1인당 입찰액(크레딧 베팅일 때만). 구성원 전원이 같은 금액을 건다
     * @param companions 함께 이용하는 사람(신청자 제외). 신청자 포함 최소 2명, 실제 재학생이어야 한다
     */
    public record ReserveBody(String roomId, Instant startsAt, Integer bidAmount, List<CompanionBody> companions) {
    }

    /** @param studentId 동행의 학번. 크레딧은 1인당 입찰액(bidAmount)을 똑같이 건다 */
    public record CompanionBody(String studentId) {
    }

    private final StudentReservationApi api;

    public ReservationController(StudentReservationApi api) {
        this.api = api;
    }

    @PostMapping("/reservations")
    public ResponseEntity<Object> reserve(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                          @RequestBody ReserveBody body) {
        SlotId slot;
        try {
            slot = new SlotId(body.roomId(), body.startsAt());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "roomId와 startsAt이 필요합니다"));
        }
        int bid = body.bidAmount() == null ? 0 : body.bidAmount();
        List<PartyMember> companions;
        try {
            companions = body.companions() == null ? List.of() : body.companions().stream()
                    .map(c -> new PartyMember(c.studentId(), 0))
                    .toList();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "동행 학번을 확인해 주세요"));
        }
        return ApiResponses.toHttp(api.reserve(ApiResponses.bearer(authorization), slot, idempotencyKey, bid, companions));
    }

    @GetMapping("/tickets/{ticketId}")
    public ResponseEntity<Object> ticket(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String ticketId) {
        return ApiResponses.toHttp(api.getTicket(ApiResponses.bearer(authorization), ticketId));
    }

    @DeleteMapping("/reservations/{reservationId}")
    public ResponseEntity<Object> cancel(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String reservationId) {
        return ApiResponses.toHttp(api.cancel(ApiResponses.bearer(authorization), reservationId));
    }
}
