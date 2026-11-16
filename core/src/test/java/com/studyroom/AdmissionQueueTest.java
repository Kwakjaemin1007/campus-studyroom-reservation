package com.studyroom;

import com.studyroom.control.performance.api.QueueMessage;
import com.studyroom.device.performance.InMemoryAdmissionQueue;
import com.studyroom.domain.ReservationRequest;
import com.studyroom.domain.SlotId;
import com.studyroom.support.MutableClock;
import org.junit.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 접수 큐(SQS FIFO 흉내): 그룹 내 순서, 그룹 단위 배타 전달, 5분 중복 제거, DLQ. */
public class AdmissionQueueTest {
    private static ReservationRequest req(String id, SlotId slot, long seq) {
        return new ReservationRequest(id, id, "u", slot, TestSupport.T0, seq, 0, 0);
    }

    @Test
    public void keepsOrderWithinAGroupAndHidesTheGroupWhileInFlight() {
        InMemoryAdmissionQueue q = new InMemoryAdmissionQueue(new MutableClock(TestSupport.T0), 3, 1);
        SlotId a = TestSupport.slot("A", 9), b = TestSupport.slot("B", 9);
        q.enqueue(req("a1", a, 1));
        q.enqueue(req("a2", a, 2));
        q.enqueue(req("b1", b, 3));

        QueueMessage first = q.poll(1).get(0);
        assertEquals("a1", first.request().id());
        // a1을 처리하는 동안 같은 슬롯의 a2는 다른 워커에게 가지 않는다.
        QueueMessage other = q.poll(1).get(0);
        assertEquals("b1", other.request().id());
        assertTrue(q.poll(1).isEmpty());

        q.ack(first);
        assertEquals("a2", q.poll(1).get(0).request().id());
    }

    @Test
    public void dropsDuplicatesForFiveMinutes() {
        MutableClock clock = new MutableClock(TestSupport.T0);
        InMemoryAdmissionQueue q = new InMemoryAdmissionQueue(clock, 3, 1);
        SlotId a = TestSupport.slot("A", 9);
        q.enqueue(req("same", a, 1));
        q.enqueue(req("same", a, 2));
        assertEquals(1, q.depth());
        // 5분이 지나면 큐는 더 이상 걸러 주지 않는다. 그래서 멱등키를 DB에도 남긴다(D10).
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        q.enqueue(req("same", a, 3));
        assertEquals(2, q.depth());
    }

    @Test
    public void poisonMessageGoesToDeadLetterQueueAndUnblocksTheSlot() {
        InMemoryAdmissionQueue q = new InMemoryAdmissionQueue(new MutableClock(TestSupport.T0), 2, 1);
        SlotId a = TestSupport.slot("A", 9);
        q.enqueue(req("poison", a, 1));
        q.enqueue(req("next", a, 2));

        List<QueueMessage> m = q.poll(1);
        assertEquals(1, m.get(0).receiveCount());
        q.nack(m.get(0));
        m = q.poll(1);
        assertEquals("poison", m.get(0).request().id());
        assertEquals(2, m.get(0).receiveCount());
        q.nack(m.get(0));

        assertEquals(1, q.deadLetters().size());
        assertEquals("next", q.poll(1).get(0).request().id());
    }
}
