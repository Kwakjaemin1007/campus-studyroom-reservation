package com.studyroom.domain;

/** 접수 직후 돌려주는 번호표. 앱은 이 id로 결과를 폴링한다. */
public record Ticket(String ticketId, Status status, String detail) {
    public enum Status { PENDING, CONFIRMED, REJECTED, CANCELED }

    public static Ticket pending(String id) {
        return new Ticket(id, Status.PENDING, null);
    }

    public static Ticket of(Reservation r) {
        Status s = switch (r.status()) {
            case REQUESTED -> Status.PENDING;
            case CONFIRMED, CHECKED_IN, COMPLETED -> Status.CONFIRMED;
            case REJECTED -> Status.REJECTED;
            case CANCELED -> Status.CANCELED;
        };
        return new Ticket(r.id(), s, r.reason());
    }
}
