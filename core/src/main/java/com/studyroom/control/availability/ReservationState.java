package com.studyroom.control.availability;

import com.studyroom.control.availability.api.*;
import com.studyroom.domain.ReservationStatus;

import java.util.Optional;

/**
 * 예약 상태 (State 패턴). 상태마다 클래스가 하나이고, 그 상태에서 허용되는 전이만 다음 상태를 돌려준다.
 * 허용되지 않는 사건은 비어 있는 값을 돌려준다. 늦게 오거나 중복된 메시지를 조용히 무시하기 위해서다.
 */
public interface ReservationState {
    ReservationStatus status();

    default Optional<ReservationStatus> confirm() {
        return Optional.empty();
    }

    default Optional<ReservationStatus> reject() {
        return Optional.empty();
    }

    default Optional<ReservationStatus> checkIn() {
        return Optional.empty();
    }

    default Optional<ReservationStatus> cancel() {
        return Optional.empty();
    }

    default Optional<ReservationStatus> expire() {
        return Optional.empty();
    }

    default Optional<ReservationStatus> complete() {
        return Optional.empty();
    }

    static ReservationState of(ReservationStatus status) {
        return switch (status) {
            case REQUESTED -> new Requested();
            case CONFIRMED -> new Confirmed();
            case CHECKED_IN -> new CheckedIn();
            case REJECTED, CANCELED, COMPLETED -> new Terminal(status);
        };
    }

    /** 접수됨: 확정·거절·취소로 갈 수 있다. */
    final class Requested implements ReservationState {
        @Override
        public ReservationStatus status() {
            return ReservationStatus.REQUESTED;
        }

        @Override
        public Optional<ReservationStatus> confirm() {
            return Optional.of(ReservationStatus.CONFIRMED);
        }

        @Override
        public Optional<ReservationStatus> reject() {
            return Optional.of(ReservationStatus.REJECTED);
        }

        @Override
        public Optional<ReservationStatus> cancel() {
            return Optional.of(ReservationStatus.CANCELED);
        }
    }

    /** 확정됨: 체크인하거나, 취소되거나, 유예가 만료돼 노쇼로 해제된다. */
    final class Confirmed implements ReservationState {
        @Override
        public ReservationStatus status() {
            return ReservationStatus.CONFIRMED;
        }

        @Override
        public Optional<ReservationStatus> checkIn() {
            return Optional.of(ReservationStatus.CHECKED_IN);
        }

        @Override
        public Optional<ReservationStatus> cancel() {
            return Optional.of(ReservationStatus.CANCELED);
        }

        @Override
        public Optional<ReservationStatus> expire() {
            return Optional.of(ReservationStatus.CANCELED);
        }
    }

    /** 입실함: 이용 종료로만 갈 수 있다. 만료 메시지가 와도 무시한다. */
    final class CheckedIn implements ReservationState {
        @Override
        public ReservationStatus status() {
            return ReservationStatus.CHECKED_IN;
        }

        @Override
        public Optional<ReservationStatus> complete() {
            return Optional.of(ReservationStatus.COMPLETED);
        }
    }

    /** 끝난 상태(REJECTED, CANCELED, COMPLETED): 어떤 사건도 받지 않는다. */
    final class Terminal implements ReservationState {
        private final ReservationStatus status;

        Terminal(ReservationStatus status) {
            this.status = status;
        }

        @Override
        public ReservationStatus status() {
            return status;
        }
    }
}
