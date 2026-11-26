package com.studyroom.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyroom.SystemConfig;
import com.studyroom.app.aws.AdmissionDeadLetterListener;
import com.studyroom.app.aws.EventBridgeDelayedMessageScheduler;
import com.studyroom.app.aws.FailOpenReadWriteLock;
import com.studyroom.app.aws.JdbcCreditRepository;
import com.studyroom.app.aws.JdbcReservationRepository;
import com.studyroom.app.aws.RedisPolicyStores;
import com.studyroom.app.aws.RedisSlotStatusCache;
import com.studyroom.app.aws.RedissonSlotLock;
import com.studyroom.app.aws.SqsAdmissionQueue;
import com.studyroom.app.aws.TimerQueueListener;
import com.studyroom.boundary.AdminPolicyApi;
import com.studyroom.boundary.QueryApi;
import com.studyroom.boundary.RateLimiter;
import com.studyroom.boundary.StudentReservationApi;
import com.studyroom.control.availability.CheckInService;
import com.studyroom.control.availability.NoShowTimeoutHandler;
import com.studyroom.control.availability.SessionEndHandler;
import com.studyroom.control.availability.OutboxPublisher;
import com.studyroom.control.availability.OutboxRelay;
import com.studyroom.control.availability.ReservationLifecycle;
import com.studyroom.control.availability.ReservationQueryService;
import com.studyroom.control.availability.api.IDelayedMessage;
import com.studyroom.control.modifiable.BookingRules;
import com.studyroom.control.modifiable.CreditLedger;
import com.studyroom.control.modifiable.ExamCalendar;
import com.studyroom.control.modifiable.ExamCreditPeriod;
import com.studyroom.control.modifiable.PolicyCatalog;
import com.studyroom.control.modifiable.PolicyRegistry;
import com.studyroom.control.modifiable.PolicySwitchManager;
import com.studyroom.control.modifiable.SemesterCreditPeriod;
import com.studyroom.control.modifiable.api.IAllocationPolicy;
import com.studyroom.control.modifiable.api.IAuthPort;
import com.studyroom.control.modifiable.api.ICreditLedger;
import com.studyroom.control.modifiable.api.IExamPeriodStore;
import com.studyroom.control.modifiable.api.INotifier;
import com.studyroom.control.performance.AllocationProcessor;
import com.studyroom.control.performance.DeadLetterHandler;
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
    IExamPeriodStore examPeriodStore(RedissonClient redisson) {
        return new RedisPolicyStores.ExamPeriodStore(redisson);
    }

    @Bean
    PolicyRegistry policyRegistry(SystemConfig config, RedissonClient redisson, IExamPeriodStore exams) {
        List<IAllocationPolicy> policies = PolicyCatalog.standard(config.pricing());   // 새 정책은 PolicyCatalog에 한 줄
        return new PolicyRegistry(policies, config.initialPolicy(),
                new RedisPolicyStores.ActivePolicyStore(redisson),
                new FailOpenReadWriteLock(redisson.getReadWriteLock("studyroom:policy-lock")), exams);
    }

    /** 크레딧은 시험 기간마다 초기화(11/25), 노쇼 패널티는 학기 단위(배정 처리기 쪽) */
    @Bean
    ICreditLedger creditLedger(JdbcTemplate jdbc, TransactionTemplate tx, SystemConfig config, Clock clock,
                               IExamPeriodStore exams) {
        return new CreditLedger(new JdbcCreditRepository(jdbc, tx), config.initialCredits(), clock,
                new ExamCreditPeriod(new SemesterCreditPeriod(), exams));
    }

    @Bean
    BookingRules bookingRules(SystemConfig config, IExamPeriodStore exams) {
        return new BookingRules(config.hours(), exams, BookingRules.DEFAULT_DAYS_AHEAD, config.maxPendingPerPerson());
    }

    @Bean
    ExamCalendar examCalendar(PolicyRegistry registry, IExamPeriodStore exams, JdbcReservationRepository repository,
                              ReservationLifecycle lifecycle, Clock clock, SystemConfig config) {
        return new ExamCalendar(registry, exams, new ReservationQueryService(repository), lifecycle, clock, config.switchLease());
    }

    @Bean(destroyMethod = "close")
    PolicySwitchManager policySwitchManager(PolicyRegistry registry, AllocationProcessor processor,
                                            RedissonClient redisson, JdbcReservationRepository repository,
                                            Clock clock, SystemConfig config) {
        return new PolicySwitchManager(registry, processor, new RedisPolicyStores.SwitchStateStore(redisson),
                () -> repository.countRequested() > 0, clock, config.switchIdleWait(), config.switchLease(),
                config.collectWindow().plus(config.switchLease()));
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
    SessionEndHandler sessionEndHandler(ReservationLifecycle lifecycle) {
        return new SessionEndHandler(lifecycle);
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
                                        ReservationLifecycle lifecycle, Clock clock, SystemConfig config,
                                        PolicyRegistry policies, PolicySwitchManager policySwitch, BookingRules rules) {
        return new ReservationIntake(queue, slotStatus, lifecycle, clock, config.minPartySize(), policies, policySwitch, rules);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    AllocationProcessor allocationProcessor(IAdmissionQueue queue, ISlotLock slotLock, RedisSlotStatusCache slotStatus,
                                            PolicyRegistry policies, ReservationLifecycle lifecycle,
                                            JdbcReservationRepository repository,
                                            IDelayedMessage delayed, Clock clock, SystemConfig config,
                                            BookingRules rules) {
        return new AllocationProcessor(queue, slotLock, slotStatus, policies, lifecycle,
                new ReservationQueryService(repository), delayed, clock,
                config.workers(), config.lockLease(), config.collectWindow(), new SemesterCreditPeriod(), rules);
    }

    /** 아래(Device)에서 위(Control)로 올라오는 이벤트의 연결: timer 큐 → 노쇼 핸들러 / 마감 일괄 배정. */
    @Bean(initMethod = "start", destroyMethod = "close")
    TimerQueueListener timerQueueListener(SqsClient sqs, StudyRoomProperties props, ObjectMapper json,
                                          NoShowTimeoutHandler noShow, SessionEndHandler sessionEnd,
                                          AllocationProcessor processor) {
        TimerQueueListener listener = new TimerQueueListener(sqs, props.getAws().getTimerQueueUrl(), json);
        listener.onMessage(DelayedMessage.Type.NO_SHOW, noShow::onGraceExpired);
        listener.onMessage(DelayedMessage.Type.SLOT_END, sessionEnd::onSlotEnded);
        listener.onMessage(DelayedMessage.Type.SLOT_CLOSE, processor::onSlotClose);
        return listener;
    }

    // ---- 끝내 처리하지 못한 메시지(DLQ) → DeadLetterHandler (Dead Letter Channel) ----

    @Bean
    DeadLetterHandler deadLetterHandler(ReservationLifecycle lifecycle) {
        return new DeadLetterHandler(lifecycle);
    }

    /** 접수 큐 DLQ: 배정을 끝내 못 한 신청 → 처리 실패로 거절·환불. */
    @Bean(initMethod = "start", destroyMethod = "close")
    AdmissionDeadLetterListener admissionDeadLetterListener(SqsClient sqs, StudyRoomProperties props, ObjectMapper json,
                                                            DeadLetterHandler handler) {
        return new AdmissionDeadLetterListener(
                new SqsAdmissionQueue(sqs, props.getAws().getAdmissionDlqUrl(), json), handler);
    }

    /** timer DLQ: 마감 판정 실패 → 보류 신청 거절·환불, 노쇼 해제 실패 → 해제 재시도. 읽는 방식은 timer 큐와 같다. */
    @Bean(initMethod = "start", destroyMethod = "close")
    TimerQueueListener timerDeadLetterListener(SqsClient sqs, StudyRoomProperties props, ObjectMapper json,
                                               NoShowTimeoutHandler noShow, SessionEndHandler sessionEnd,
                                               DeadLetterHandler handler) {
        TimerQueueListener listener = new TimerQueueListener(sqs, props.getAws().getTimerDlqUrl(), json);
        listener.onMessage(DelayedMessage.Type.SLOT_CLOSE, handler::onSlotCloseFailed);
        listener.onMessage(DelayedMessage.Type.NO_SHOW, noShow::onGraceExpired);
        listener.onMessage(DelayedMessage.Type.SLOT_END, sessionEnd::onSlotEnded);
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
    AdminPolicyApi adminPolicyApi(IAuthPort auth, PolicySwitchManager policySwitch, PolicyRegistry registry,
                                  ExamCalendar exams) {
        return new AdminPolicyApi(auth, policySwitch, registry, exams);
    }

    /** 조회용 창구. 저장소를 읽기만 하는 조회 서비스와 큐 길이를 넘겨 준다. */
    @Bean
    QueryApi queryApi(IAuthPort auth, PolicyRegistry registry, PolicySwitchManager policySwitch, ICreditLedger credits,
                      JdbcReservationRepository repository, IAdmissionQueue queue, SystemConfig config,
                      ExamCalendar exams) {
        return new QueryApi(auth, registry, policySwitch, credits, new ReservationQueryService(repository),
                queue::depth, config.grace(), config.collectWindow(), config.minPartySize(), exams, config.hours());
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
