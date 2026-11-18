package com.studyroom.control.availability;

import com.studyroom.control.availability.api.IReservationQuery;
import com.studyroom.control.availability.api.IReservationRepository;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.SlotId;
import com.studyroom.support.CampusTime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 조회 전용 서비스. 저장소를 읽기만 한다.
 * 점유 슬롯은 저장소의 findOccupiedSlots()를 받아 기간으로 거른다. 예약이 많아지면
 * 저장소에 기간 조건 조회를 추가하고 여기만 바꾼다(인터페이스는 그대로).
 */
public final class ReservationQueryService implements IReservationQuery {
    private final IReservationRepository repository;

    public ReservationQueryService(IReservationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Reservation> find(String reservationId) {
        return repository.findById(reservationId);
    }

    @Override
    public int slotsUsedOnDay(String userId, LocalDate day) {
        Instant from = CampusTime.startOfDay(day);
        Instant to = CampusTime.startOfDay(day.plusDays(1));
        return (int) repository.findByUserAndStartsBetween(userId, from, to).stream()
                .filter(ReservationQueryService::countsTowardDailyLimit)
                .count();
    }

    static boolean countsTowardDailyLimit(Reservation r) {
        return switch (r.status()) {
            case REQUESTED, CONFIRMED, CHECKED_IN, COMPLETED -> true;
            case CANCELED -> "NO_SHOW".equals(r.reason());
            case REJECTED -> false;
        };
    }

    @Override
    public List<SlotId> occupiedSlots(Instant from, Instant to) {
        return repository.findOccupiedSlots().stream()
                .filter(s -> !s.startsAt().isBefore(from) && s.startsAt().isBefore(to))
                .sorted(Comparator.comparing(SlotId::startsAt).thenComparing(SlotId::roomId))
                .toList();
    }
}
