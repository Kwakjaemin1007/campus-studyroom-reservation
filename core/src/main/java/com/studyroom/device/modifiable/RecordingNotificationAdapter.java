package com.studyroom.device.modifiable;

import com.studyroom.control.modifiable.api.INotifier;
import com.studyroom.domain.NotificationEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** 알림 서비스의 가짜 구현. 보낸 알림을 기록만 한다. failNext(n)으로 발송 실패를 주입한다. */
public final class RecordingNotificationAdapter implements INotifier {
    public record Sent(String userId, NotificationEvent event) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();

    @Override
    public void notify(String userId, NotificationEvent event) {
        if (failures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new IllegalStateException("notification provider is down");
        }
        sent.add(new Sent(userId, event));
    }

    public void failNext(int count) {
        failures.set(count);
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public long count(NotificationEvent.Type type) {
        return sent.stream().filter(s -> s.event().type() == type).count();
    }
}
