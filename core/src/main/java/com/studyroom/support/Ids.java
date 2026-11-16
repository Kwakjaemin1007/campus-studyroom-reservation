package com.studyroom.support;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class Ids {
    private Ids() {
    }

    /** (userId, 멱등키) → 요청 id. 결정적이라 재시도는 같은 id가 된다. */
    public static String requestId(String userId, String idempotencyKey) {
        return "R-" + UUID.nameUUIDFromBytes((userId + "\n" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }
}
