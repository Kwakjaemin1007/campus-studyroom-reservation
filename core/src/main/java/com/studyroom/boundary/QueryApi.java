package com.studyroom.boundary;

import com.studyroom.control.availability.api.IReservationQuery;
import com.studyroom.control.modifiable.api.AllocationTiming;
import com.studyroom.control.modifiable.api.IAllocationPolicy;
import com.studyroom.control.modifiable.api.AuthenticationException;
import com.studyroom.control.modifiable.api.IAuthPort;
import com.studyroom.control.modifiable.api.ICreditLedger;
import com.studyroom.control.modifiable.api.IPolicyProvider;
import com.studyroom.control.modifiable.api.IPolicySwitch;
import com.studyroom.control.modifiable.api.PolicyHandle;
import com.studyroom.control.modifiable.api.SwitchPhase;
import com.studyroom.domain.Identity;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

/**
 * 조회용 창구 (Boundary). 화면(앱·웹)이 그리는 데 필요한 값만 읽어 준다.
 * 아무 상태도 바꾸지 않으므로 유입 제한(RateLimiter)을 걸지 않는다. 예약 접수의 초당 한도를
 * 조회 폴링이 깎아 먹지 않게 하기 위해서다.
 *
 * 대기열 길이는 큐(Device)가 아는 값이라 조립 루트가 IntSupplier로 넘겨 준다.
 * Boundary가 Device를 직접 보지 않는 규칙을 지키기 위해서다.
 */
public final class QueryApi {
    /** 한 번에 조회할 수 있는 최대 기간. */
    static final Duration MAX_RANGE = Duration.ofDays(8);

    /** GET /policy 응답. maxSlotsPerDay는 하루 한도(슬롯=1시간)이고 제한이 없으면 null. graceSeconds·collectWindowSeconds는 화면의 타이머가 서버 설정과 어긋나지 않게 같이 준다. */
    public record PolicyView(String name, int version, AllocationTiming timing, SwitchPhase phase,
                             long graceSeconds, long collectWindowSeconds,
                             Integer maxSlotsPerDay) {
    }

    /** GET /slots 응답의 한 줄. 차 있는 슬롯만 내려 준다(없는 슬롯은 비어 있는 것). */
    public record SlotView(String roomId, Instant startsAt, boolean taken) {
    }

    /** GET /credits/me 응답. balance는 지금 쓸 수 있는 크레딧, held는 입찰로 묶인 크레딧, renewsAt은 잔액이 지급량으로 초기화되는 다음 갱신 시각. */
    public record CreditView(int balance, int held, Instant renewsAt) {
    }

    /** GET /reservations/{id} 응답. 번호표(Ticket)가 4가지로 줄여 주는 상태를 6가지 그대로 준다. */
    public record ReservationView(String id, ReservationStatus status, String reason,
                                  String roomId, Instant startsAt,
                                  String policyName, int policyVersion, int bidAmount, int chargedAmount,
                                  Instant requestedAt, Instant updatedAt, Instant checkInDeadline) {
    }

    /** GET /queue 응답. 전체 슬롯을 합친 대기·처리 중 요청 수다. 내 앞의 순번이 아니다. */
    public record QueueView(int depth) {
    }

    private final IAuthPort auth;
    private final IPolicyProvider policies;
    private final IPolicySwitch policySwitch;
    private final ICreditLedger credits;
    private final IReservationQuery reservations;
    private final IntSupplier queueDepth;
    private final Duration grace;
    private final Duration collectWindow;

    public QueryApi(IAuthPort auth, IPolicyProvider policies, IPolicySwitch policySwitch, ICreditLedger credits,
                    IReservationQuery reservations, IntSupplier queueDepth, Duration grace, Duration collectWindow) {
        this.auth = auth;
        this.policies = policies;
        this.policySwitch = policySwitch;
        this.credits = credits;
        this.reservations = reservations;
        this.queueDepth = queueDepth;
        this.grace = grace;
        this.collectWindow = collectWindow;
    }

    /** GET /policy — 학생도 읽는다. 관리자용 GET /admin/policy와 달리 판정 시점과 전환 상태를 함께 준다. */
    public ApiResponse<PolicyView> policy(String token) {
        if (verify(token).isEmpty()) return unauthorized();
        PolicyHandle h = policies.current();
        return ApiResponse.ok(new PolicyView(h.name(), h.version(), h.policy().timing(), policySwitch.phase(),
                grace.toSeconds(), collectWindow.toSeconds(),
                h.policy().maxSlotsPerDay() == IAllocationPolicy.UNLIMITED ? null : h.policy().maxSlotsPerDay()));
    }

    /**
     * GET /slots?roomId=A,B&from=..&to=..
     *
     * @param roomIds 쉼표로 이은 방 id. 비어 있으면 모든 방
     * @param from    ISO-8601 시각(포함)
     * @param to      ISO-8601 시각(제외)
     */
    public ApiResponse<List<SlotView>> slots(String token, String roomIds, String from, String to) {
        if (verify(token).isEmpty()) return unauthorized();
        Instant start;
        Instant end;
        try {
            start = Instant.parse(String.valueOf(from));
            end = Instant.parse(String.valueOf(to));
        } catch (DateTimeParseException e) {
            return ApiResponse.error(400, "from과 to는 ISO-8601 시각이어야 합니다");
        }
        if (!end.isAfter(start)) return ApiResponse.error(400, "to는 from보다 뒤여야 합니다");
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            return ApiResponse.error(400, "한 번에 " + MAX_RANGE.toDays() + "일까지만 조회할 수 있습니다");
        }
        Set<String> rooms = roomIds == null ? Set.of()
                : Arrays.stream(roomIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        List<SlotView> taken = reservations.occupiedSlots(start, end).stream()
                .filter(s -> rooms.isEmpty() || rooms.contains(s.roomId()))
                .map(s -> new SlotView(s.roomId(), s.startsAt(), true))
                .toList();
        return ApiResponse.ok(taken);
    }

    /** GET /credits/me */
    public ApiResponse<CreditView> credits(String token) {
        Optional<Identity> who = verify(token);
        if (who.isEmpty()) return unauthorized();
        String userId = who.get().userId();
        return ApiResponse.ok(new CreditView(credits.balance(userId), credits.held(userId), credits.renewsAt()));
    }

    /**
     * GET /reservations/{id} — 본인 예약(또는 관리자)만 볼 수 있다.
     * 아직 큐에서 기다리는 요청은 저장소에 없으므로 404다. 그동안은 번호표(GET /tickets/{id})가 PENDING을 준다.
     */
    public ApiResponse<ReservationView> reservation(String token, String reservationId) {
        Optional<Identity> who = verify(token);
        if (who.isEmpty()) return unauthorized();
        Optional<Reservation> found = reservations.find(reservationId);
        if (found.isEmpty()) return ApiResponse.error(404, "예약이 없습니다");
        Reservation r = found.get();
        if (!who.get().admin() && !r.userId().equals(who.get().userId())) {
            return ApiResponse.error(403, "본인 예약이 아닙니다");
        }
        SlotId slot = r.slot();
        return ApiResponse.ok(new ReservationView(r.id(), r.status(), r.reason(), slot.roomId(), slot.startsAt(),
                r.policyName(), r.policyVersion(), r.bidAmount(), r.chargedAmount(),
                r.requestedAt(), r.updatedAt(), slot.startsAt().plus(grace)));
    }

    /** GET /queue */
    public ApiResponse<QueueView> queue(String token) {
        if (verify(token).isEmpty()) return unauthorized();
        return ApiResponse.ok(new QueueView(Math.max(0, queueDepth.getAsInt())));
    }

    private Optional<Identity> verify(String token) {
        try {
            return Optional.of(auth.verify(token));
        } catch (AuthenticationException e) {
            return Optional.empty();
        }
    }

    private static <T> ApiResponse<T> unauthorized() {
        return ApiResponse.error(401, "로그인이 필요합니다");
    }
}
