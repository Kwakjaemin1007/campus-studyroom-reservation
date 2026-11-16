package com.studyroom.control.modifiable.api;

import com.studyroom.domain.NotificationEvent;

/** 알림 서비스(푸시/이메일) 연동 포트. 실패하면 예외를 던지고, 재발행은 OutboxRelay가 맡는다. */
public interface INotifier {
    void notify(String userId, NotificationEvent event);
}
