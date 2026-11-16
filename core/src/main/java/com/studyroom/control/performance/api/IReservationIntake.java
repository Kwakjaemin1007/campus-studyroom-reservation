package com.studyroom.control.performance.api;

import com.studyroom.domain.Ticket;

/** 접수 창구. 판단은 하지 않고 번호표만 준다(202 + 티켓). */
public interface IReservationIntake {
    /**
     * @throws IllegalArgumentException  요청이 잘못됐을 때 (400)
     * @throws SlotClosedException       이미 마감된 슬롯 (409)
     * @throws QueueUnavailableException 큐 장애 (503)
     */
    Ticket submit(SubmitCommand command, String idempotencyKey);

    Ticket getTicket(String ticketId);
}
