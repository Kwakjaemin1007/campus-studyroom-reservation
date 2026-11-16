package com.studyroom;

import com.studyroom.boundary.ApiResponse;
import com.studyroom.domain.Reservation;
import com.studyroom.domain.ReservationStatus;
import com.studyroom.domain.SlotId;
import com.studyroom.domain.Ticket;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

final class TestSupport {
    static final Instant T0 = Instant.parse("2026-12-01T00:00:00Z");

    private TestSupport() {
    }

    static SlotId slot(String room, int hour) {
        return new SlotId(room, T0.plusSeconds(3600L * (24 + hour)));
    }

    static void await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("condition not met within " + timeoutMillis + "ms");
            Thread.sleep(2);
        }
    }

    /** 예약 한 건을 넣고 처리가 끝날 때까지 기다린 뒤 티켓 id를 돌려준다. */
    static String reserveAndWait(StudyRoomSystem sys, String user, SlotId slot, String key, int bid) throws Exception {
        ApiResponse<Ticket> res = sys.studentApi.reserve("user:" + user, slot, key, bid);
        assertEquals(202, res.status());
        sys.awaitQuiescent(5000);
        return res.body().ticketId();
    }

    /** 슬롯을 차지한 예약이 슬롯당 하나를 넘지 않는지 센다. @return 이중 예약된 슬롯 수 */
    static long doubleBookedSlots(List<Reservation> all) {
        Map<String, Integer> perSlot = new HashMap<>();
        for (Reservation r : all) {
            if (r.status().occupiesSlot()) perSlot.merge(r.slot().key(), 1, Integer::sum);
        }
        return perSlot.values().stream().filter(n -> n > 1).count();
    }

    static ReservationStatus statusOf(StudyRoomSystem sys, String id) {
        return sys.repository.findById(id).orElseThrow().status();
    }
}
