package com.studyroom;

import com.studyroom.boundary.AdminPolicyApi;
import com.studyroom.boundary.QueryApi;
import com.studyroom.boundary.RateLimiter;
import com.studyroom.boundary.StudentReservationApi;
import com.studyroom.control.availability.CheckInService;
import com.studyroom.control.availability.NoShowTimeoutHandler;
import com.studyroom.control.availability.OutboxPublisher;
import com.studyroom.control.availability.OutboxRelay;
import com.studyroom.control.availability.ReservationLifecycle;
import com.studyroom.control.availability.ReservationQueryService;
import com.studyroom.control.modifiable.CreditBettingPolicy;
import com.studyroom.control.modifiable.CreditLedger;
import com.studyroom.control.modifiable.FcfsPolicy;
import com.studyroom.control.modifiable.api.IAllocationPolicy;
import com.studyroom.control.modifiable.api.INotifier;
import com.studyroom.control.modifiable.PolicyRegistry;
import com.studyroom.control.modifiable.PolicySwitchManager;
import com.studyroom.control.modifiable.PriorityPolicy;
import com.studyroom.control.performance.AllocationProcessor;
import com.studyroom.control.performance.ReservationIntake;
import com.studyroom.device.availability.InMemoryDelayedMessageScheduler;
import com.studyroom.device.availability.InMemoryReservationRepository;
import com.studyroom.device.modifiable.InMemoryActivePolicyStore;
import com.studyroom.device.modifiable.InMemorySwitchStateStore;
import com.studyroom.device.modifiable.QrCheckInAdapter;
import com.studyroom.device.modifiable.RecordingNotificationAdapter;
import com.studyroom.device.modifiable.StubSsoAuthAdapter;
import com.studyroom.device.performance.InMemoryAdmissionQueue;
import com.studyroom.device.performance.InMemorySlotLock;
import com.studyroom.device.performance.InMemorySlotStatusCache;
import com.studyroom.domain.DelayedMessage;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 조립 루트. Device를 전부 메모리 구현으로 끼워 한 프로세스에서 시스템 전체를 띄운다.
 * 테스트와 데모가 이것을 쓴다. AWS에 올릴 때는 app 모듈의 Spring 설정이 같은 조립을
 * SQS·Redis·RDS·EventBridge 구현으로 한다.
 */
public final class StudyRoomSystem implements AutoCloseable {
    public final Clock clock;
    public final SystemConfig config;

    // Device
    public final InMemoryAdmissionQueue queue;
    public final InMemorySlotLock slotLock = new InMemorySlotLock();
    public final InMemorySlotStatusCache slotCache = new InMemorySlotStatusCache();
    public final InMemoryReservationRepository repository;
    public final InMemoryDelayedMessageScheduler scheduler;
    public final StubSsoAuthAdapter sso = new StubSsoAuthAdapter();
    public final RecordingNotificationAdapter notifications = new RecordingNotificationAdapter();
    public final QrCheckInAdapter qrDevice;

    // Control — 변경 용이성
    public final PolicyRegistry policyRegistry;
    public final PolicySwitchManager policySwitch;
    public final InMemorySwitchStateStore switchState = new InMemorySwitchStateStore();
    public final CreditLedger credits;

    // Control — 가용성
    public final ReservationLifecycle lifecycle;
    public final CheckInService checkIn;
    public final NoShowTimeoutHandler noShowHandler;
    public final OutboxRelay outboxRelay;
    public final ReservationQueryService reservationQuery;

    // Control — 성능
    public final ReservationIntake intake;
    public final AllocationProcessor processor;

    // Boundary
    public final StudentReservationApi studentApi;
    public final AdminPolicyApi adminApi;
    public final QueryApi queryApi;

    public StudyRoomSystem(Clock clock, SystemConfig config) {
        this(clock, config, null);
    }

    /** @param notifierOverride 알림 Adapter를 다른 구현으로 갈아 끼울 때 (QAS-4 확인용). null이면 기본 구현 */
    public StudyRoomSystem(Clock clock, SystemConfig config, INotifier notifierOverride) {
        this.clock = clock;
        this.config = config;
        INotifier notifier = notifierOverride != null ? notifierOverride : notifications;

        queue = new InMemoryAdmissionQueue(clock, config.maxReceiveCount(), 20);
        repository = new InMemoryReservationRepository(clock);
        scheduler = new InMemoryDelayedMessageScheduler(clock, config.maxReceiveCount());

        List<IAllocationPolicy> policies = List.of(new FcfsPolicy(), new PriorityPolicy(),
                new CreditBettingPolicy(config.pricing()));
        policyRegistry = new PolicyRegistry(policies, config.initialPolicy(),
                new InMemoryActivePolicyStore(), new ReentrantReadWriteLock());
        credits = new CreditLedger(config.initialCredits());

        OutboxPublisher publisher = new OutboxPublisher(repository, scheduler, notifier);
        lifecycle = new ReservationLifecycle(repository, publisher, scheduler, slotCache, credits, clock, config.grace());
        checkIn = new CheckInService(lifecycle);
        noShowHandler = new NoShowTimeoutHandler(lifecycle);
        outboxRelay = new OutboxRelay(repository, publisher);
        reservationQuery = new ReservationQueryService(repository);

        intake = new ReservationIntake(queue, slotCache, lifecycle, clock);
        processor = new AllocationProcessor(queue, slotLock, slotCache, policyRegistry, lifecycle, scheduler, clock,
                config.workers(), config.lockLease(), config.collectWindow());

        policySwitch = new PolicySwitchManager(policyRegistry, processor, switchState,
                this::collectionOpen, clock, config.switchIdleWait(), config.switchLease());

        // 아래(Device)에서 위(Control)로는 이벤트로만 올라온다.
        scheduler.onMessage(DelayedMessage.Type.NO_SHOW, noShowHandler::onGraceExpired);
        scheduler.onMessage(DelayedMessage.Type.SLOT_CLOSE, processor::onSlotClose);
        qrDevice = new QrCheckInAdapter(checkIn);

        RateLimiter limiter = new RateLimiter(clock, config.perUserRate(), config.globalRate());
        studentApi = new StudentReservationApi(sso, limiter, intake, lifecycle);
        adminApi = new AdminPolicyApi(sso, policySwitch, policyRegistry);
        queryApi = new QueryApi(sso, policyRegistry, policySwitch, credits, reservationQuery, queue::depth,
                config.grace(), config.collectWindow());
    }

    /** 접수 창이 열려 보류 중인 요청이 있는가. 있으면 정책 전환을 막는다. */
    private boolean collectionOpen() {
        return repository.count(com.studyroom.domain.ReservationStatus.REQUESTED) > 0;
    }

    public StudyRoomSystem start() {
        processor.start();
        return this;
    }

    /** 큐가 비고 처리 중인 배정이 없어질 때까지 기다린다. */
    public void awaitQuiescent(long timeoutMillis) throws InterruptedException, TimeoutException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int stable = 0;
        while (stable < 3) {
            if (System.currentTimeMillis() > deadline) {
                throw new TimeoutException("queue depth=" + queue.depth() + ", inFlight=" + processor.inFlightCount());
            }
            stable = (queue.depth() == 0 && processor.inFlightCount() == 0) ? stable + 1 : 0;
            Thread.sleep(2);
        }
    }

    /** Redis 복구 뒤 슬롯 캐시를 DB에서 다시 만든다. */
    public void rebuildSlotCache() {
        slotCache.rebuild(repository.findOccupiedSlots());
    }

    @Override
    public void close() {
        processor.close();
        policySwitch.close();
        outboxRelay.close();
        scheduler.close();
    }
}
