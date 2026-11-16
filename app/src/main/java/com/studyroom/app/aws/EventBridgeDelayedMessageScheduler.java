package com.studyroom.app.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyroom.control.availability.api.IDelayedMessage;
import com.studyroom.domain.DelayedMessage;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.ActionAfterCompletion;
import software.amazon.awssdk.services.scheduler.model.ConflictException;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindow;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindowMode;
import software.amazon.awssdk.services.scheduler.model.ResourceNotFoundException;
import software.amazon.awssdk.services.scheduler.model.Target;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 지연 메시지의 EventBridge Scheduler 구현.
 * 예약마다 1회성 at() 일정을 만들고, 시각이 되면 Scheduler가 timer 큐(SQS)에 메시지를 넣는다.
 *  - 일정 이름 = key라서 같은 key로 다시 만들면 ConflictException → 성공으로 본다(멱등)
 *  - ActionAfterCompletion = DELETE: 끝난 일정이 계정의 일정 개수 한도를 차지하지 않게 한다
 *  - at()의 정밀도는 분 단위다. 그래서 노쇼 해제 목표를 "만료 후 60초 내"로 잡았다
 */
public final class EventBridgeDelayedMessageScheduler implements IDelayedMessage {
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final SchedulerClient scheduler;
    private final SqsClient sqs;
    private final ObjectMapper json;
    private final Clock clock;
    private final String group;
    private final String timerQueueUrl;
    private final String timerQueueArn;
    private final String roleArn;

    public EventBridgeDelayedMessageScheduler(SchedulerClient scheduler, SqsClient sqs, ObjectMapper json, Clock clock,
                                              String group, String timerQueueUrl, String timerQueueArn, String roleArn) {
        this.scheduler = scheduler;
        this.sqs = sqs;
        this.json = json;
        this.clock = clock;
        this.group = group;
        this.timerQueueUrl = timerQueueUrl;
        this.timerQueueArn = timerQueueArn;
        this.roleArn = roleArn;
    }

    @Override
    public void schedule(String key, Instant at, DelayedMessage payload) {
        final String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        if (!at.isAfter(clock.instant())) {
            // 이미 지난 시각이면(예: Relay의 뒤늦은 재발행) 일정을 만들지 않고 바로 보낸다.
            sqs.sendMessage(b -> b.queueUrl(timerQueueUrl).messageBody(body));
            return;
        }
        try {
            scheduler.createSchedule(b -> b.name(scheduleName(key)).groupName(group)
                    .scheduleExpression("at(" + AT.format(at) + ")")
                    .scheduleExpressionTimezone("UTC")
                    .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                    .target(Target.builder().arn(timerQueueArn).roleArn(roleArn).input(body).build())
                    .actionAfterCompletion(ActionAfterCompletion.DELETE));
        } catch (ConflictException alreadyScheduled) {
            // 같은 이름의 일정이 이미 있다.
        }
    }

    @Override
    public void cancel(String key) {
        try {
            scheduler.deleteSchedule(b -> b.name(scheduleName(key)).groupName(group));
        } catch (ResourceNotFoundException alreadyGone) {
            // 이미 실행됐거나 지워졌다.
        }
    }

    /** 일정 이름은 [0-9a-zA-Z-_.] 64자까지라서 그 밖의 글자는 바꾸고, 길면 해시로 줄인다. */
    static String scheduleName(String key) {
        String name = key.replaceAll("[^0-9a-zA-Z_.-]", "_");
        if (name.length() <= 64) return name;
        return name.substring(0, 27) + "-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
