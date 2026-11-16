package com.studyroom.app.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyroom.domain.DelayedMessage;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * timer 큐(SQS 표준 큐)를 읽어 지연 메시지를 수신자에게 넘긴다.
 * 처리에 성공한 메시지만 지운다. 실패한 메시지는 다시 전달되고, 큐의 redrive 정책에 따라 DLQ로 간다.
 * 표준 큐는 같은 메시지를 두 번 줄 수 있으므로 수신자는 멱등해야 한다(조건부 UPDATE).
 */
public final class TimerQueueListener implements AutoCloseable {
    private final SqsClient sqs;
    private final String queueUrl;
    private final ObjectMapper json;
    private final Map<DelayedMessage.Type, Consumer<String>> handlers = new EnumMap<>(DelayedMessage.Type.class);
    private volatile boolean running;
    private Thread thread;

    public TimerQueueListener(SqsClient sqs, String queueUrl, ObjectMapper json) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.json = json;
    }

    public synchronized void onMessage(DelayedMessage.Type type, Consumer<String> handler) {
        handlers.put(type, handler);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "timer-queue-listener");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                List<Message> messages = sqs.receiveMessage(b -> b.queueUrl(queueUrl)
                        .maxNumberOfMessages(10).waitTimeSeconds(10)).messages();
                for (Message m : messages) handle(m);
            } catch (RuntimeException e) {
                sleepQuietly(1000);
            }
        }
    }

    private void handle(Message m) {
        try {
            DelayedMessage message = json.readValue(m.body(), DelayedMessage.class);
            Consumer<String> handler;
            synchronized (this) {
                handler = handlers.get(message.type());
            }
            if (handler == null) return;
            handler.accept(message.subject());
            sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(m.receiptHandle()));
        } catch (Exception e) {
            // 지우지 않으면 가시성 제한 시간이 지난 뒤 다시 전달된다.
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) thread.interrupt();
    }
}
