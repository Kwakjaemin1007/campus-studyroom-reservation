package com.studyroom.app.aws;

import com.studyroom.control.performance.api.IAdmissionQueue;
import com.studyroom.control.performance.api.IDeadLetterListener;
import com.studyroom.control.performance.api.QueueMessage;

import java.util.List;

/**
 * 접수 큐의 DLQ(SQS FIFO)를 읽어 수신자(IDeadLetterListener)에게 넘긴다 (Dead Letter Channel).
 * DLQ도 같은 형식의 FIFO 큐라서 읽기는 SqsAdmissionQueue를 DLQ 주소로 하나 더 만들어 쓴다.
 * 수신자가 성공한 메시지만 지운다. 실패하면 지우지 않아 가시성 제한 시간 뒤 다시 읽힌다(DB 장애 등).
 */
public final class AdmissionDeadLetterListener implements AutoCloseable {
    private final IAdmissionQueue deadLetterQueue;
    private final IDeadLetterListener listener;
    private volatile boolean running;
    private Thread thread;

    public AdmissionDeadLetterListener(IAdmissionQueue deadLetterQueue, IDeadLetterListener listener) {
        this.deadLetterQueue = deadLetterQueue;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "admission-dlq-listener");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                List<QueueMessage> messages = deadLetterQueue.poll(10);
                for (QueueMessage m : messages) handle(m);
            } catch (RuntimeException e) {
                sleepQuietly(1000);
            }
        }
    }

    private void handle(QueueMessage m) {
        try {
            listener.onDeadLetter(m.request());
            deadLetterQueue.ack(m);
        } catch (RuntimeException e) {
            // 지우지 않는다. 가시성 제한 시간이 지나면 다시 전달된다.
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
