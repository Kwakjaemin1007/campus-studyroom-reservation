package com.studyroom.control.performance.api;

public class QueueUnavailableException extends RuntimeException {
    public QueueUnavailableException(String message) {
        super(message);
    }
}
