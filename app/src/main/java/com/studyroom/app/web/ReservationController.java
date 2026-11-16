package com.studyroom.app.web;

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
    public record ReserveBody(String roomId, Instant startsAt, Integer bidAmount) {
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
        return ApiResponses.toHttp(api.reserve(ApiResponses.bearer(authorization), slot, idempotencyKey, bid));
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
