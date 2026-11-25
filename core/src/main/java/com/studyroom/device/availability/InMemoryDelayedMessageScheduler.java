package com.studyroom.device.availability;

import com.studyroom.control.availability.api.IDelayedMessage;
import com.studyroom.domain.DelayedMessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * EventBridge Scheduler(1회성 일정) → SQS → 핸들러 경로를 흉내 낸 메모리 구현.
 *  - 일정 이름(key)이 같으면 다시 만들지 않는다
 *  - 전달이 끝난 일정은 지운다(ActionAfterCompletion = DELETE)
 *  - 핸들러가 계속 실패하면 maxReceiveCount 뒤 DLQ로 격리하고, 종류별 DLQ 수신자(onDeadLetter)에게 알린다
 * tick()을 직접 부르거나 start()로 주기 실행한다.
 */
public final class InMemoryDelayedMessageScheduler implements IDelayedMessage, AutoCloseable {
    private static final class Entry {
        final Instant at;
        final DelayedMessage payload;
        int attempts;

        Entry(Instant at, DelayedMessage payload) {
            this.at = at;
            this.payload = payload;
        }
    }

    private final Clock clock;
    private final int maxReceiveCount;
    private final Map<String, Entry> schedules = new ConcurrentHashMap<>();
    private final Map<DelayedMessage.Type, Consumer<String>> handlers = new EnumMap<>(DelayedMessage.Type.class);
    private final List<DelayedMessage> deadLetters = new CopyOnWriteArrayList<>();
    private final Map<DelayedMessage.Type, Consumer<String>> deadLetterHandlers = new EnumMap<>(DelayedMessage.Type.class);
    private ScheduledExecutorService ticker;
    private volatile boolean up = true;

    public InMemoryDelayedMessageScheduler(Clock clock, int maxReceiveCount) {
        this.clock = clock;
        this.maxReceiveCount = maxReceiveCount;
    }

    /** 메시지 종류별 수신자를 등록한다(아래에서 위로 올라가는 이벤트의 Listener). */
    public synchronized void onMessage(DelayedMessage.Type type, Consumer<String> handler) {
        handlers.put(type, handler);
    }

    @Override
    public void schedule(String key, Instant at, DelayedMessage payload) {
        if (!up) throw new IllegalStateException("scheduler is down");
        schedules.putIfAbsent(key, new Entry(at, payload));
    }

    @Override
    public void cancel(String key) {
        if (!up) throw new IllegalStateException("scheduler is down");
        schedules.remove(key);
    }

    /** 시각이 된 일정을 전달한다. @return 전달에 성공한 건수 */
    public int tick() {
        Instant now = clock.instant();
        List<Map.Entry<String, Entry>> due = new ArrayList<>();
        for (Map.Entry<String, Entry> e : schedules.entrySet()) {
            if (!e.getValue().at.isAfter(now)) due.add(e);
        }
        int delivered = 0;
        for (Map.Entry<String, Entry> e : due) {
            Entry entry = e.getValue();
            Consumer<String> handler;
            synchronized (this) {
                handler = handlers.get(entry.payload.type());
            }
            if (handler == null) continue;
            if (!schedules.remove(e.getKey(), entry)) continue;   // 그 사이 취소됐다
            try {
                entry.attempts++;
                handler.accept(entry.payload.subject());
                delivered++;
            } catch (RuntimeException ex) {
                if (entry.attempts >= maxReceiveCount) {
                    deadLetters.add(entry.payload);
                    deliverDeadLetter(entry.payload);
                } else {
                    schedules.putIfAbsent(e.getKey(), entry);   // 다음 tick에 다시 전달
                }
            }
        }
        return delivered;
    }

    /**
     * DLQ로 간 메시지를 종류별 수신자에게 넘긴다(Dead Letter Channel). 종류마다 정리 방법이 다르다:
     * 마감 판정(SLOT_CLOSE)은 보류 신청 거절·환불, 노쇼(NO_SHOW)는 해제 재시도.
     * SQS에서는 timer DLQ를 읽는 리스너가 같은 일을 한다.
     */
    public synchronized void onDeadLetter(DelayedMessage.Type type, Consumer<String> handler) {
        deadLetterHandlers.put(type, handler);
    }

    /** DLQ에 남은 메시지를 수신자에게 다시 넘긴다(SQS의 DLQ 재수신에 해당). @return 처리된 건수 */
    public int retryDeadLetters() {
        int done = 0;
        for (DelayedMessage m : deadLetters()) {
            if (deliverDeadLetter(m)) done++;
        }
        return done;
    }

    /** 수신자가 성공하면 DLQ에서 지우고, 실패하면 남겨 둔다. */
    private boolean deliverDeadLetter(DelayedMessage message) {
        Consumer<String> handler;
        synchronized (this) {
            handler = deadLetterHandlers.get(message.type());
        }
        if (handler == null) return false;
        try {
            handler.accept(message.subject());
        } catch (RuntimeException e) {
            return false;
        }
        deadLetters.remove(message);
        return true;
    }

    public synchronized void start(Duration period) {
        if (ticker != null) return;
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "delayed-message");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException ignored) {
                // 다음 주기에 다시 시도한다.
            }
        }, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    public int pendingCount() {
        return schedules.size();
    }

    public boolean isScheduled(String key) {
        return schedules.containsKey(key);
    }

    public List<DelayedMessage> deadLetters() {
        return List.copyOf(deadLetters);
    }

    /** 장애 주입: false면 schedule/cancel이 실패한다. */
    public void setAvailable(boolean available) {
        this.up = available;
    }

    @Override
    public synchronized void close() {
        if (ticker != null) ticker.shutdownNow();
        ticker = null;
    }
}
