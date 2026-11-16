package com.studyroom.app.aws;

import com.studyroom.control.modifiable.api.ActivePolicy;
import com.studyroom.control.modifiable.api.IActivePolicyStore;
import com.studyroom.control.modifiable.api.ISwitchStateStore;
import com.studyroom.control.modifiable.api.SwitchPhase;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;

import java.time.Instant;

/** 정책 전환에 필요한 공유 상태의 Redis 구현. 인스턴스가 여러 대여도 같은 값을 본다. */
public final class RedisPolicyStores {
    private RedisPolicyStores() {
    }

    /** 현재 정책의 이름과 버전. Redis가 죽으면 마지막으로 읽은 값을 쓴다(배정은 계속된다). */
    public static final class ActivePolicyStore implements IActivePolicyStore {
        private final RBucket<String> bucket;
        private volatile ActivePolicy lastKnown;

        public ActivePolicyStore(RedissonClient redisson) {
            this.bucket = redisson.getBucket("studyroom:active-policy", StringCodec.INSTANCE);
        }

        @Override
        public ActivePolicy get() {
            try {
                String v = bucket.get();
                if (v == null) return null;
                int colon = v.lastIndexOf(':');
                lastKnown = new ActivePolicy(v.substring(0, colon), Integer.parseInt(v.substring(colon + 1)));
            } catch (RedisException e) {
                if (lastKnown == null) throw e;
            }
            return lastKnown;
        }

        @Override
        public void set(ActivePolicy active) {
            bucket.set(active.name() + ":" + active.version());
            lastKnown = active;
        }
    }

    /** 전환 상태와 임대 만료 시각. compareAndSet은 Redis에서 원자적으로 실행된다. */
    public static final class SwitchStateStore implements ISwitchStateStore {
        private final RBucket<String> phase;
        private final RBucket<String> lease;

        public SwitchStateStore(RedissonClient redisson) {
            this.phase = redisson.getBucket("studyroom:switch-phase", StringCodec.INSTANCE);
            this.lease = redisson.getBucket("studyroom:switch-lease", StringCodec.INSTANCE);
            this.phase.setIfAbsent(SwitchPhase.ACTIVE.name());
        }

        @Override
        public SwitchPhase get() {
            String v = phase.get();
            return v == null ? SwitchPhase.ACTIVE : SwitchPhase.valueOf(v);
        }

        @Override
        public boolean compareAndSet(SwitchPhase expect, SwitchPhase update) {
            return phase.compareAndSet(expect.name(), update.name());
        }

        @Override
        public void setLeaseExpiry(Instant until) {
            lease.set(String.valueOf(until.toEpochMilli()));
        }

        @Override
        public Instant leaseExpiry() {
            String v = lease.get();
            return v == null ? null : Instant.ofEpochMilli(Long.parseLong(v));
        }
    }
}
