package com.studyroom.domain;

/** 사용자에게 보내는 알림 한 건. */
public record NotificationEvent(Type type, String reservationId, String message) {
    public enum Type { CONFIRMED, REJECTED, CANCELED, NO_SHOW_RELEASED }
}
