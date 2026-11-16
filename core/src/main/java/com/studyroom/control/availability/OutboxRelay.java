package com.studyroom.control.availability;

import com.studyroom.control.availability.api.*;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 발행하지 못한 타이머·알림을 주기적으로 다시 발행한다. 커밋 직후 워커가 죽어도 누락이 없다. */
public final class OutboxRelay implements AutoCloseable {
    private final IReservationRepository repository;
    private final OutboxPublisher publisher;
    private ScheduledExecutorService scheduler;

    public OutboxRelay(IReservationRepository repository, OutboxPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    /** @return 이번에 발행에 성공한 건수 */
    public int relayOnce() {
        return publisher.publishAll(repository.findUnpublishedOutbox());
    }

    public synchronized void start(Duration period) {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-relay");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                relayOnce();
            } catch (RuntimeException ignored) {
                // 다음 주기에 다시 시도한다.
            }
        }, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
    }
}
