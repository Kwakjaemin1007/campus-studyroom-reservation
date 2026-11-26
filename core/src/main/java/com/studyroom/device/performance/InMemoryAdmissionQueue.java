package com.studyroom.device.performance;

import com.studyroom.control.performance.api.IAdmissionQueue;
import com.studyroom.control.performance.api.IDeadLetterListener;
import com.studyroom.control.performance.api.QueueMessage;
import com.studyroom.control.performance.api.QueueUnavailableException;
import com.studyroom.domain.ReservationRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQS FIFO를 흉내 낸 메모리 큐.
 *  - 메시지 그룹(= 슬롯 키) 안에서는 도착순, 처리 중인 그룹은 다른 워커에게 주지 않는다
 *  - 중복 제거 ID(= 멱등키)는 5분 동안 기억한다
 *  - maxReceiveCount를 넘게 실패한 메시지는 DLQ로 옮겨 뒤 요청을 막지 않게 하고, 수신자(onDeadLetter)에게 알린다
 */
public final class InMemoryAdmissionQueue implements IAdmissionQueue {
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(5);

    private static final class Entry {
        final ReservationRequest request;
        int receiveCount;

        Entry(ReservationRequest request) {
            this.request = request;
        }
    }

    private final Clock clock;
    private final int maxReceiveCount;
    private final long pollWaitMillis;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Map<String, Deque<Entry>> groups = new HashMap<>();
    private final Deque<String> ready = new ArrayDeque<>();
    private final Map<String, Integer> outstandingByGroup = new HashMap<>();
    private final Map<String, Entry> outstanding = new HashMap<>();
    private final Map<String, Instant> dedup = new HashMap<>();
    private final List<ReservationRequest> deadLetters = new ArrayList<>();
    private long handleSeq;
    private int size;
    private Instant lastDedupSweep = Instant.EPOCH;
    private volatile boolean up = true;
    private volatile IDeadLetterListener deadLetterListener;

    public InMemoryAdmissionQueue(Clock clock, int maxReceiveCount, long pollWaitMillis) {
        this.clock = clock;
        this.maxReceiveCount = maxReceiveCount;
        this.pollWaitMillis = pollWaitMillis;
    }

    @Override
    public void enqueue(ReservationRequest request) {
        if (!up) throw new QueueUnavailableException("queue is down");
        lock.lock();
        try {
            Instant now = clock.instant();
            sweepDedup(now);
            Instant seen = dedup.get(request.id());
            if (seen != null && seen.plus(DEDUP_WINDOW).isAfter(now)) return;   // 받았지만 다시 전달하지 않는다
            dedup.put(request.id(), now);
            String group = request.slot().key();
            Deque<Entry> q = groups.computeIfAbsent(group, g -> new ArrayDeque<>());
            q.addLast(new Entry(request));
            size++;
            if (q.size() == 1 && !outstandingByGroup.containsKey(group)) {
                ready.addLast(group);
                available.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<QueueMessage> poll(int max) {
        lock.lock();
        try {
            if (ready.isEmpty()) {
                try {
                    available.await(pollWaitMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return List.of();
                }
            }
            String group = ready.pollFirst();
            if (group == null) return List.of();
            Deque<Entry> q = groups.get(group);
            List<QueueMessage> out = new ArrayList<>();
            while (out.size() < max && q != null && !q.isEmpty()) {
                Entry e = q.pollFirst();
                e.receiveCount++;
                String handle = "h" + (++handleSeq);
                outstanding.put(handle, e);
                out.add(new QueueMessage(handle, group, e.request, e.receiveCount));
            }
            if (out.isEmpty()) {
                groups.remove(group);
            } else {
                outstandingByGroup.put(group, out.size());
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void ack(QueueMessage message) {
        lock.lock();
        try {
            if (outstanding.remove(message.receiptHandle()) == null) return;
            size--;
            settle(message.groupId());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void nack(QueueMessage message) {
        ReservationRequest dead = null;
        lock.lock();
        try {
            Entry e = outstanding.remove(message.receiptHandle());
            if (e == null) return;
            if (e.receiveCount >= maxReceiveCount) {
                deadLetters.add(e.request);
                size--;
                dead = e.request;
            } else {
                groups.computeIfAbsent(message.groupId(), g -> new ArrayDeque<>()).addFirst(e);
            }
            settle(message.groupId());
        } finally {
            lock.unlock();
        }
        if (dead != null) deliverDeadLetter(dead);   // 락 밖에서 부른다(수신자가 DB를 쓴다)
    }

    /** DLQ로 옮긴 신청을 받을 수신자(아래에서 위로 올라가는 사건). SQS에서는 DLQ를 읽는 리스너가 같은 일을 한다. */
    public void onDeadLetter(IDeadLetterListener listener) {
        this.deadLetterListener = listener;
    }

    /** DLQ에 남은 신청을 수신자에게 다시 넘긴다(SQS의 DLQ 재수신에 해당). @return 처리된 건수 */
    public int retryDeadLetters() {
        int done = 0;
        for (ReservationRequest r : deadLetters()) {
            if (deliverDeadLetter(r)) done++;
        }
        return done;
    }

    /** 수신자가 성공하면 DLQ에서 지우고, 실패하면 남겨 둔다. */
    private boolean deliverDeadLetter(ReservationRequest request) {
        IDeadLetterListener listener = deadLetterListener;
        if (listener == null) return false;
        try {
            listener.onDeadLetter(request);
        } catch (RuntimeException e) {
            return false;
        }
        lock.lock();
        try {
            deadLetters.remove(request);
        } finally {
            lock.unlock();
        }
        return true;
    }

    /** 그룹에서 처리 중인 메시지가 모두 끝나면 그룹을 다시 전달 가능 상태로 돌린다. */
    private void settle(String group) {
        int left = outstandingByGroup.merge(group, -1, Integer::sum);
        if (left > 0) return;
        outstandingByGroup.remove(group);
        Deque<Entry> q = groups.get(group);
        if (q == null || q.isEmpty()) {
            groups.remove(group);
        } else {
            ready.addLast(group);
            available.signal();
        }
    }

    private void sweepDedup(Instant now) {
        if (lastDedupSweep.plusSeconds(30).isAfter(now)) return;
        lastDedupSweep = now;
        for (Iterator<Instant> it = dedup.values().iterator(); it.hasNext(); ) {
            if (!it.next().plus(DEDUP_WINDOW).isAfter(now)) it.remove();
        }
    }

    @Override
    public int depth() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    public List<ReservationRequest> deadLetters() {
        lock.lock();
        try {
            return List.copyOf(deadLetters);
        } finally {
            lock.unlock();
        }
    }

    /** 장애 주입: false면 enqueue가 실패한다. */
    public void setAvailable(boolean available) {
        this.up = available;
    }
}
