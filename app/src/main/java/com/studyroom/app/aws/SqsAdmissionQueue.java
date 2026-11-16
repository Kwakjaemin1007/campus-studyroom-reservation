package com.studyroom.app.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyroom.control.performance.api.IAdmissionQueue;
import com.studyroom.control.performance.api.QueueMessage;
import com.studyroom.control.performance.api.QueueUnavailableException;
import com.studyroom.domain.ReservationRequest;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 접수 큐의 SQS FIFO 구현 (고처리량 모드).
 *  - MessageGroupId = 슬롯 키: 같은 슬롯은 도착순으로, 한 번에 한 소비자에게만
 *  - MessageDeduplicationId = 요청 id(멱등키에서 만든 값): 5분 안의 중복은 SQS가 버린다
 *  - 계속 실패하는 메시지는 큐의 redrive 정책(maxReceiveCount)으로 FIFO DLQ에 간다
 */
public final class SqsAdmissionQueue implements IAdmissionQueue {
    private final SqsClient sqs;
    private final String queueUrl;
    private final ObjectMapper json;

    public SqsAdmissionQueue(SqsClient sqs, String queueUrl, ObjectMapper json) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.json = json;
    }

    @Override
    public void enqueue(ReservationRequest request) {
        try {
            String body = json.writeValueAsString(request);
            sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody(body)
                    .messageGroupId(request.slot().key())
                    .messageDeduplicationId(request.id()));
        } catch (SdkException | JsonProcessingException e) {
            throw new QueueUnavailableException("SQS 적재 실패: " + e.getMessage());
        }
    }

    @Override
    public List<QueueMessage> poll(int max) {
        List<Message> messages = sqs.receiveMessage(b -> b.queueUrl(queueUrl)
                .maxNumberOfMessages(Math.min(Math.max(max, 1), 10))
                .waitTimeSeconds(1)
                .messageSystemAttributeNames(MessageSystemAttributeName.MESSAGE_GROUP_ID,
                        MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).messages();
        List<QueueMessage> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            Map<MessageSystemAttributeName, String> attrs = m.attributes();
            try {
                ReservationRequest request = json.readValue(m.body(), ReservationRequest.class);
                String count = attrs.get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
                out.add(new QueueMessage(m.receiptHandle(), attrs.get(MessageSystemAttributeName.MESSAGE_GROUP_ID),
                        request, count == null ? 1 : Integer.parseInt(count)));
            } catch (JsonProcessingException e) {
                // 해석할 수 없는 메시지는 그대로 두면 redrive 정책에 따라 DLQ로 간다.
            }
        }
        return out;
    }

    @Override
    public void ack(QueueMessage message) {
        sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
    }

    @Override
    public void nack(QueueMessage message) {
        // 가시성 제한을 0으로 돌려 바로 다시 전달되게 한다.
        sqs.changeMessageVisibility(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()).visibilityTimeout(0));
    }

    @Override
    public int depth() {
        Map<QueueAttributeName, String> a = sqs.getQueueAttributes(b -> b.queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
        return Integer.parseInt(a.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"))
                + Integer.parseInt(a.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE, "0"));
    }
}
