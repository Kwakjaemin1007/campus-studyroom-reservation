package com.studyroom.control.modifiable.api;

public class UnknownPolicyException extends RuntimeException {
    public UnknownPolicyException(String name) {
        super("unknown policy: " + name);
    }
}
