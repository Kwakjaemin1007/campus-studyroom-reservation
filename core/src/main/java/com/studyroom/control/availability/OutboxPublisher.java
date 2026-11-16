package com.studyroom.control.availability;

import com.studyroom.control.availability.api.*;
import com.studyroom.control.modifiable.api.INotifier;
import com.studyroom.domain.DelayedMessage;
import com.studyroom.domain.NotificationEvent;
import com.studyroom.domain.OutboxEntry;

import java.util.List;

/** outbox 한 줄을 실제로 내보낸다. 성공하면 발행 완료로 표시하고, 실패하면 남겨 둔다. */
public final class OutboxPublisher {
    private final IReservationRepository repository;
    private final IDelayedMessage delayed;
    private final INotifier notifier;

    public OutboxPublisher(IReservationRepository repository, IDelayedMessage delayed, INotifier notifier) {
        this.repository = repository;
        this.delayed = delayed;
        this.notifier = notifier;
    }

    public static String noShowKey(String reservationId) {
        return "noshow:" + reservationId;
    }

    /** @return 발행에 성공한 건수 */
    public int publishAll(List<OutboxEntry> entries) {
        int ok = 0;
        for (OutboxEntry e : entries) {
            try {
                publish(e);
                repository.markOutboxPublished(e.id());
                ok++;
            } catch (RuntimeException ex) {
                // 남겨 두면 OutboxRelay가 다시 시도한다.
            }
        }
        return ok;
    }

    private void publish(OutboxEntry e) {
        switch (e.kind()) {
            case SCHEDULE_NOSHOW_TIMER -> delayed.schedule(noShowKey(e.reservationId()), e.fireAt(),
                    new DelayedMessage(DelayedMessage.Type.NO_SHOW, e.reservationId()));
            case NOTIFY -> notifier.notify(e.userId(),
                    new NotificationEvent(e.eventType(), e.reservationId(), e.message()));
        }
    }
}
