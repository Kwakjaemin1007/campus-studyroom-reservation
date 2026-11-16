package com.studyroom.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyroom.SystemConfig;
import com.studyroom.app.aws.EventBridgeDelayedMessageScheduler;
import com.studyroom.app.aws.FailOpenReadWriteLock;
import com.studyroom.app.aws.JdbcCreditLedger;
import com.studyroom.app.aws.JdbcReservationRepository;
import com.studyroom.app.aws.RedisPolicyStores;
import com.studyroom.app.aws.RedisSlotStatusCache;
import com.studyroom.app.aws.RedissonSlotLock;
import com.studyroom.app.aws.SqsAdmissionQueue;
import com.studyroom.app.aws.TimerQueueListener;
import com.studyroom.boundary.AdminPolicyApi;
import com.studyroom.boundary.RateLimiter;
import com.studyroom.boundary.StudentReservationApi;
import com.studyroom.control.availability.CheckInService;
import com.studyroom.control.availability.NoShowTimeoutHandler;
import com.studyroom.control.availability.OutboxPublisher;
import com.studyroom.control.availability.OutboxRelay;
import com.studyroom.control.availability.ReservationLifecycle;
import com.studyroom.control.availability.api.IDelayedMessage;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.FcfsPolicy;
import com.studyroom.control.modifiable.PolicyRegistry;
import com.studyroom.control.modifiable.PolicySwitchManager;
import com.studyroom.control.modifiable.PriorityPolicy;
import com.studyroom.control.modifiable.api.IAllocationPolicy;
import com.studyroom.control.modifiable.api.IAuthPort;
import com.studyroom.control.modifiable.api.ICreditLedger;
import com.studyroom.control.modifiable.api.INotifier;
import com.studyroom.control.performance.AllocationProcessor;
import com.studyroom.control.performance.ReservationIntake;
import com.studyroom.control.performance.api.IAdmissionQueue;
import com.studyroom.control.performance.api.ISlotLock;
import com.studyroom.device.modifiable.QrCheckInAdapter;
import com.studyroom.device.modifiable.RecordingNotificationAdapter;
import com.studyroom.device.modifiable.StubSsoAuthAdapter;
import com.studyroom.domain.DelayedMessage;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Clock;
import java.util.List;

/**
 * aws 프로필: 코어의 Control·Boundary는 그대로 두고 Device만 AWS 구현으로 끼운다.
 * 조립 순서는 코어의 StudyRoomSystem과 같다.
 *
 * SSO·알림은 외부 시스템이라 인터페이스만 정의했다. 실제 연동 Adapter가 생기면
 * 아래 authPort()와 notifier() 두 빈만 바꾸면 된다(QAS-4).
 */
@Configuration
@Profile("aws")
@EnableConfigurationProperties(StudyRoomProperties.class)
public class AwsConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SystemConfig systemConfig(StudyRoomProperties props) {
        return props.toSystemConfig();
    }

    // ---- AWS 클라이언트 (리전과 자격 증명은 기본 공급자 체인에서 읽는다) ----

    @Bean(destroyMethod = "close")
    SqsClient sqsClient() {
        return SqsClient.create();
    }

    @Bean(destroyMethod = "close")
    SchedulerClient schedulerClient() {
        return SchedulerClient.create();
    }

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(StudyRoomProperties props) {
        Config config = new Config();
        config.useSingleServer().setAddress(props.getAws().getRedisAddress());
        return Redisson.create(config);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }

    // ---- Device ----

    @Bean
    IAdmissionQueue admissionQueue(SqsClient sqs, StudyRoomProperties props, ObjectMapper json) {
        return new SqsAdmissionQueue(sqs, props.getAws().getAdmissionQueueUrl(), json);
    }

    @Bean
    ISlotLock slotLock(RedissonClient redisson) {
        return new RedissonSlotLock(redisson);
    }

    @Bean
    RedisSlotStatusCache slotStatusCache(RedissonClient redisson) {
        return new RedisSlotStatusCache(redisson);
    }

    @Bean
    JdbcReservationRepository reservationRepository(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        return new JdbcReservationRepository(jdbc, tx, clock);
    }

    @Bean
    IDelayedMessage delayedMessage(SchedulerClient scheduler, SqsClient sqs, ObjectMapper json, Clock clock,
                                   StudyRoomProperties props) {
        StudyRoomProperties.Aws aws = props.getAws();
        return new EventBridgeDelayedMessageScheduler(scheduler, sqs, json, clock, aws.getSchedulerGroup(),
                aws.getTimerQueueUrl(), aws.getTimerQueueArn(), aws.getSchedulerRoleArn());
    }

    @Bean
    IAuthPort authPort() {
        return new StubSsoAuthAdapter();
    }

    @Bean
    INotifier notifier() {
        return new RecordingNotificationAdapter();
    }

    // ---- Control: 변경 용이성 ----

    @Bean
    PolicyRegistry policyRegistry(SystemConfig config, RedissonClient redisson) {
        List<IAllocationPolicy> policies = List.of(new FcfsPolicy(), new PriorityPolicy(),
                new CreditBettingPolicy(config.pricing()));
        return new PolicyRegistry(policies, config.initialPolicy(),
                new RedisPolicyStores.ActivePolicyStore(redisson),
                new FailOpenReadWriteLock(redisson.getReadWriteLock("studyroom:policy-lock")));
    }

    @Bean
    ICreditLedger creditLedger(JdbcTemplate jdbc, TransactionTemplate tx, SystemConfig config) {
        return new JdbcCreditLedger(jdbc, tx, config.initialCredits());
    }

    @Bean(destroyMethod = "close")
    PolicySwitchManager policySwitchManager(PolicyRegistry registry, AllocationProcessor processor,
                                            RedissonClient redisson, JdbcReservationRepository repository,
                                            Clock clock, SystemConfig config) {
        return new PolicySwitchManager(registry, processor, new RedisPolicyStores.SwitchStateStore(redisson),
                () -> repository.countRequested() > 0, clock, config.switchIdleWait(), config.switchLease());
    }

    // ---- Control: 가용성 ----

    @Bean
    OutboxPublisher outboxPublisher(JdbcReservationRepository repository, IDelayedMessage delayed, INotifier notifier) {
        return new OutboxPublisher(repository, delayed, notifier);
    }

    @Bean
    ReservationLifecycle reservationLifecycle(JdbcReservationRepository repository, OutboxPublisher publisher,
                                              IDelayedMessage delayed, RedisSlotStatusCache slotStatus,
                                              ICreditLedger credits, Clock clock, SystemConfig config) {
        return new ReservationLifecycle(repository, publisher, delayed, slotStatus, credits, clock, config.grace());
    }

    @Bean
    CheckInService checkInService(ReservationLifecycle lifecycle) {
        return new CheckInService(lifecycle);
    }

    @Bean
    NoShowTimeoutHandler noShowTimeoutHandler(ReservationLifecycle lifecycle) {
        return new NoShowTimeoutHandler(lifecycle);
    }

    @Bean
    OutboxRelay outboxRelay(JdbcReservationRepository repository, OutboxPublisher publisher) {
        return new OutboxRelay(repository, publisher);
    }

    @Bean
    QrCheckInAdapter qrCheckInAdapter(CheckInService checkIn) {
        return new QrCheckInAdapter(checkIn);
    }

    // ---- Control: 성능 ----

    @Bean
    ReservationIntake reservationIntake(IAdmissionQueue queue, RedisSlotStatusCache slotStatus,
                                        ReservationLifecycle lifecycle, Clock clock) {
        return new ReservationIntake(queue, slotStatus, lifecycle, clock);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    AllocationProcessor allocationProcessor(IAdmissionQueue queue, ISlotLock slotLock, RedisSlotStatusCache slotStatus,
                                            PolicyRegistry policies, ReservationLifecycle lifecycle,
                                            IDelayedMessage delayed, Clock clock, SystemConfig config) {
        return new AllocationProcessor(queue, slotLock, slotStatus, policies, lifecycle, delayed, clock,
                config.workers(), config.lockLease(), config.collectWindow());
    }

    /** 아래(Device)에서 위(Control)로 올라오는 이벤트의 연결: timer 큐 → 노쇼 핸들러 / 마감 일괄 배정. */
    @Bean(initMethod = "start", destroyMethod = "close")
    TimerQueueListener timerQueueListener(SqsClient sqs, StudyRoomProperties props, ObjectMapper json,
                                          NoShowTimeoutHandler noShow, AllocationProcessor processor) {
        TimerQueueListener listener = new TimerQueueListener(sqs, props.getAws().getTimerQueueUrl(), json);
        listener.onMessage(DelayedMessage.Type.NO_SHOW, noShow::onGraceExpired);
        listener.onMessage(DelayedMessage.Type.SLOT_CLOSE, processor::onSlotClose);
        return listener;
    }

    // ---- Boundary ----

    @Bean
    StudentReservationApi studentReservationApi(IAuthPort auth, ReservationIntake intake, ReservationLifecycle lifecycle,
                                                Clock clock, SystemConfig config) {
        return new StudentReservationApi(auth, new RateLimiter(clock, config.perUserRate(), config.globalRate()),
                intake, lifecycle);
    }

    @Bean
    AdminPolicyApi adminPolicyApi(IAuthPort auth, PolicySwitchManager policySwitch, PolicyRegistry registry) {
        return new AdminPolicyApi(auth, policySwitch, registry);
    }

    /** Redis 복구 뒤 60초 안에 슬롯 캐시를 DB에서 다시 만든다(QAS-5). */
    @Bean
    SlotCacheRebuilder slotCacheRebuilder(RedisSlotStatusCache cache, JdbcReservationRepository repository) {
        return new SlotCacheRebuilder(cache, repository);
    }

    public static class SlotCacheRebuilder {
        private final RedisSlotStatusCache cache;
        private final JdbcReservationRepository repository;

        SlotCacheRebuilder(RedisSlotStatusCache cache, JdbcReservationRepository repository) {
            this.cache = cache;
            this.repository = repository;
        }

        @Scheduled(fixedDelay = 30_000)
        public void rebuildIfNeeded() {
            cache.rebuildIfDegraded(repository.findOccupiedSlots());
        }
    }
}
