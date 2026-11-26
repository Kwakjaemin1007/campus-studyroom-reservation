package com.studyroom;

import com.studyroom.control.availability.ReservationState;
import com.studyroom.control.availability.api.IReservationLifecycle.TransitionResult;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static com.studyroom.domain.ReservationStatus.CANCELED;
import static com.studyroom.domain.ReservationStatus.CHECKED_IN;
import static com.studyroom.domain.ReservationStatus.COMPLETED;
import static com.studyroom.domain.ReservationStatus.CONFIRMED;
import static com.studyroom.domain.ReservationStatus.REJECTED;
import static com.studyroom.domain.ReservationStatus.REQUESTED;
import static org.junit.Assert.assertEquals;

/** State 패턴: 상태마다 허용된 전이만 다음 상태를 돌려준다. */
public class ReservationStateTest {
    @Test
    public void allowedTransitions() {
        assertEquals(Optional.of(CONFIRMED), ReservationState.of(REQUESTED).confirm());
        assertEquals(Optional.of(REJECTED), ReservationState.of(REQUESTED).reject());
        assertEquals(Optional.of(CANCELED), ReservationState.of(REQUESTED).cancel());
        assertEquals(Optional.of(CHECKED_IN), ReservationState.of(CONFIRMED).checkIn());
        assertEquals(Optional.of(CANCELED), ReservationState.of(CONFIRMED).cancel());
        assertEquals(Optional.of(CANCELED), ReservationState.of(CONFIRMED).expire());
        assertEquals(Optional.of(COMPLETED), ReservationState.of(CHECKED_IN).complete());
    }

    @Test
    public void everythingElseIsIgnored() {
        assertEquals(Optional.empty(), ReservationState.of(REQUESTED).checkIn());
        assertEquals(Optional.empty(), ReservationState.of(REQUESTED).expire());
        assertEquals(Optional.empty(), ReservationState.of(CONFIRMED).confirm());
        assertEquals(Optional.empty(), ReservationState.of(CHECKED_IN).expire());
        assertEquals(Optional.empty(), ReservationState.of(CHECKED_IN).cancel());
        for (ReservationStatus terminal : new ReservationStatus[]{REJECTED, CANCELED, COMPLETED}) {
            ReservationState s = ReservationState.of(terminal);
            assertEquals(terminal, s.status());
            assertEquals(Optional.empty(), s.confirm());
            assertEquals(Optional.empty(), s.reject());
            assertEquals(Optional.empty(), s.checkIn());
            assertEquals(Optional.empty(), s.cancel());
            assertEquals(Optional.empty(), s.expire());
            assertEquals(Optional.empty(), s.complete());
        }
    }

    @Test
    public void fullLifecycleThroughTheSingleGate() throws Exception {
        try (StudyRoomSystem sys = new StudyRoomSystem(Clock.systemUTC(), SystemConfig.defaults().withMinPartySize(1)).start()) {
            SlotId slot = TestSupport.slot("R1", 9);
            String id = TestSupport.reserveAndWait(sys, "a", slot, "k1", 0);
            Instant inWindow = slot.startsAt();   // 입실은 시작 10분 전 ~ 유예 종료 전에만 된다
            assertEquals(TransitionResult.NO_OP, sys.lifecycle.complete(id));
            assertEquals(TransitionResult.APPLIED, sys.lifecycle.checkIn(id, inWindow));
            assertEquals(TransitionResult.NO_OP, sys.lifecycle.checkIn(id, inWindow));
            assertEquals(TransitionResult.NO_OP, sys.lifecycle.cancel(id, "late"));
            assertEquals(TransitionResult.APPLIED, sys.lifecycle.complete(id));
            assertEquals(COMPLETED, TestSupport.statusOf(sys, id));
            assertEquals(TransitionResult.NOT_FOUND, sys.lifecycle.checkIn("nope", Instant.now()));
        }
    }
}
