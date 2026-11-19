import type { ReservationDto, ReserveBodyDto, TicketDto } from '@/api/dto/backend';
import type { HttpClient } from '@/api/http/httpClient';
import type { IdempotencyKeyRing, SingleFlight } from '@/api/http/idempotency';
import { withRetry } from '@/api/http/retry';
import type { ReservationGateway } from '@/api/ports';
import { ApiError, isApiError } from '@/domain/errors';
import { slotKey, type ReserveInput, type TicketView } from '@/domain/reservation';
import { toReservationDetail, toTicketView } from './mappers';

export interface ReservationAdapterDeps {
  readonly http: HttpClient;
  readonly keys: IdempotencyKeyRing;
  readonly flights: SingleFlight;
  /** 의도를 사용자별로 나누기 위해 쓴다 */
  readonly currentUserId: () => string | null;
}

/** 같은 사용자가 같은 슬롯을 신청하는 것을 하나의 의도로 본다. */
function intentOf(userId: string | null, input: ReserveInput): string {
  return `reserve|${userId ?? 'anonymous'}|${slotKey(input.slot)}`;
}

function requireTicket(data: TicketDto | null): TicketDto {
  if (!data || typeof data.ticketId !== 'string') {
    throw new ApiError({ kind: 'server', serverMessage: '번호표 응답 형식이 올바르지 않습니다.' });
  }
  return data;
}

export function createReservationAdapter(deps: ReservationAdapterDeps): ReservationGateway {
  const { http, keys, flights } = deps;

  return {
    reserve(input, options) {
      const intent = intentOf(deps.currentUserId(), input);

      // 진행 중인 같은 신청이 있으면 그것을 돌려준다(연타 차단).
      return flights.run(intent, async (): Promise<TicketView> => {
        const idempotencyKey = keys.acquire(intent);
        const body: ReserveBodyDto = {
          roomId: input.slot.roomId,
          startsAt: input.slot.startsAt.toISOString(),
          bidAmount: input.bidAmount,
          companions: input.companions.map((c) => ({ studentId: c.studentId, bidAmount: c.bid })),
        };

        try {
          // 재시도는 모두 같은 키로 나간다. 서버에 이미 접수됐다면 같은 번호표가 돌아온다.
          const res = await withRetry(
            () =>
              http.request<TicketDto>({
                method: 'POST',
                path: '/reservations',
                body,
                headers: { 'Idempotency-Key': idempotencyKey },
                signal: options?.signal,
              }),
            { signal: options?.signal, onRetry: ({ delayMs }) => options?.onRetry?.(delayMs) },
          );
          const ticket = toTicketView(requireTicket(res.data));
          if (ticket.settled) keys.release(intent);
          else keys.bindTicket(intent, ticket.ticketId);
          return ticket;
        } catch (e) {
          if (isApiError(e)) {
            // 서버가 확실히 거절한 요청은 키를 버린다. 다음 신청은 새 요청이어야 한다.
            // 연결 실패·시간 초과는 접수됐는지 알 수 없으므로 키를 남겨 같은 키로 다시 보내게 한다.
            if (!e.retryable && e.kind !== 'aborted') keys.release(intent);
            if (e.kind === 'conflict') throw e.as('slot_closed');
          }
          throw e;
        }
      });
    },

    async getTicket(ticketId, options) {
      const res = await http.request<TicketDto>({
        method: 'GET',
        path: `/tickets/${encodeURIComponent(ticketId)}`,
        signal: options?.signal,
      });
      const ticket = toTicketView(requireTicket(res.data));
      if (ticket.settled) keys.releaseByTicket(ticket.ticketId);
      return ticket;
    },

    async getDetail(reservationId, options) {
      try {
        const res = await http.request<ReservationDto>({
          method: 'GET',
          path: `/reservations/${encodeURIComponent(reservationId)}`,
          signal: options?.signal,
        });
        return res.data ? toReservationDetail(res.data) : null;
      } catch (e) {
        // 404: 아직 큐에서 기다리는 요청(저장 전)이거나, 이 조회가 없는 예전 서버다.
        if (isApiError(e) && e.kind === 'not_found') return null;
        throw e;
      }
    },

    async cancel(reservationId, options) {
      try {
        await http.request<null>({
          method: 'DELETE',
          path: `/reservations/${encodeURIComponent(reservationId)}`,
          signal: options?.signal,
        });
        keys.releaseByTicket(reservationId);
      } catch (e) {
        if (isApiError(e) && e.kind === 'conflict') throw e.as('not_cancelable');
        throw e;
      }
    },
  };
}
