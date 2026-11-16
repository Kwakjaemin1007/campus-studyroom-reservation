package com.studyroom.app.config;

import com.studyroom.control.availability.OutboxRelay;
import com.studyroom.control.modifiable.PolicySwitchManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 주기 작업: 미발행 outbox 재발행, 임대가 만료된 전환 복구. 둘 다 풀 스캔이 아니다. */
@Component
public class MaintenanceTasks {
    private final OutboxRelay outboxRelay;
    private final PolicySwitchManager policySwitch;

    public MaintenanceTasks(OutboxRelay outboxRelay, PolicySwitchManager policySwitch) {
        this.outboxRelay = outboxRelay;
        this.policySwitch = policySwitch;
    }

    @Scheduled(fixedDelay = 5000)
    public void relayOutbox() {
        outboxRelay.relayOnce();
    }

    @Scheduled(fixedDelay = 5000)
    public void recoverStuckSwitch() {
        policySwitch.recoverIfLeaseExpired();
    }
}
