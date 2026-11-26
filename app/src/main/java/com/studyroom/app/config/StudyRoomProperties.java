package com.studyroom.app.config;

import com.studyroom.SystemConfig;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** application.yml의 studyroom.* 값. */
@ConfigurationProperties(prefix = "studyroom")
public class StudyRoomProperties {
    private int workers = 16;
    private int graceMinutes = 10;
    private int collectWindowMinutes = 10;
    private int initialCredits = 100;
    private CreditBettingPolicy.Pricing pricing = CreditBettingPolicy.Pricing.FIRST_PRICE;
    private int perUserRate = 5;
    private int globalRate = 5000;
    private String initialPolicy = "FCFS";
    private int minPartySize = 2;
    private final Aws aws = new Aws();

    public SystemConfig toSystemConfig() {
        SystemConfig d = SystemConfig.defaults();
        return new SystemConfig(workers, d.lockLease(), Duration.ofMinutes(graceMinutes),
                Duration.ofMinutes(collectWindowMinutes), initialCredits, pricing, perUserRate, globalRate,
                d.maxReceiveCount(), d.switchIdleWait(), d.switchLease(), initialPolicy, minPartySize);
    }

    public static class Aws {
        private String admissionQueueUrl;
        private String timerQueueUrl;
        private String admissionDlqUrl;
        private String timerDlqUrl;
        private String timerQueueArn;
        private String schedulerRoleArn;
        private String schedulerGroup = "default";
        private String redisAddress;

        public String getAdmissionQueueUrl() { return admissionQueueUrl; }
        public void setAdmissionQueueUrl(String v) { this.admissionQueueUrl = v; }
        public String getTimerQueueUrl() { return timerQueueUrl; }
        public void setTimerQueueUrl(String v) { this.timerQueueUrl = v; }
        public String getAdmissionDlqUrl() { return admissionDlqUrl; }
        public void setAdmissionDlqUrl(String v) { this.admissionDlqUrl = v; }
        public String getTimerDlqUrl() { return timerDlqUrl; }
        public void setTimerDlqUrl(String v) { this.timerDlqUrl = v; }
        public String getTimerQueueArn() { return timerQueueArn; }
        public void setTimerQueueArn(String v) { this.timerQueueArn = v; }
        public String getSchedulerRoleArn() { return schedulerRoleArn; }
        public void setSchedulerRoleArn(String v) { this.schedulerRoleArn = v; }
        public String getSchedulerGroup() { return schedulerGroup; }
        public void setSchedulerGroup(String v) { this.schedulerGroup = v; }
        public String getRedisAddress() { return redisAddress; }
        public void setRedisAddress(String v) { this.redisAddress = v; }
    }

    public Aws getAws() { return aws; }
    public int getWorkers() { return workers; }
    public void setWorkers(int v) { this.workers = v; }
    public int getGraceMinutes() { return graceMinutes; }
    public void setGraceMinutes(int v) { this.graceMinutes = v; }
    public int getCollectWindowMinutes() { return collectWindowMinutes; }
    public void setCollectWindowMinutes(int v) { this.collectWindowMinutes = v; }
    public int getInitialCredits() { return initialCredits; }
    public void setInitialCredits(int v) { this.initialCredits = v; }
    public CreditBettingPolicy.Pricing getPricing() { return pricing; }
    public void setPricing(CreditBettingPolicy.Pricing v) { this.pricing = v; }
    public int getPerUserRate() { return perUserRate; }
    public void setPerUserRate(int v) { this.perUserRate = v; }
    public int getGlobalRate() { return globalRate; }
    public void setGlobalRate(int v) { this.globalRate = v; }
    public String getInitialPolicy() { return initialPolicy; }
    public void setInitialPolicy(String v) { this.initialPolicy = v; }
    public int getMinPartySize() { return minPartySize; }
    public void setMinPartySize(int v) { this.minPartySize = v; }
}
