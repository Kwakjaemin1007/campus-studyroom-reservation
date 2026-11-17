import type { QrScanBodyDto, QrScanDto } from '@/api/dto/backend';
import type { HttpClient } from '@/api/http/httpClient';
import type { QrDeviceGateway } from '@/api/ports';
import { isApiError } from '@/domain/errors';
import type { CheckInResult } from '@/domain/reservation';
import { toCheckInOutcome } from './mappers';

/** 기기가 보내는 QR 형식. 백엔드 QrCheckInAdapter 의 PREFIX 와 같아야 한다. */
export const QR_PREFIX = 'RSV:';

export function toQrPayload(reservationId: string): string {
  return QR_PREFIX + reservationId.trim();
}

function reservationIdOf(payload: string): string | null {
  return payload.startsWith(QR_PREFIX) ? payload.slice(QR_PREFIX.length) : null;
}

export function createQrDeviceAdapter(http: HttpClient, now: () => Date = () => new Date()): QrDeviceGateway {
  async function scanRaw(payload: string, signal?: AbortSignal): Promise<CheckInResult> {
    const body: QrScanBodyDto = { payload };
    const reservationId = reservationIdOf(payload);
    try {
      // 이 엔드포인트는 기기용 웹훅이라 사용자 토큰을 붙이지 않는다.
      const res = await http.request<QrScanDto>({
        method: 'POST',
        path: '/devices/qr/scan',
        body,
        auth: false,
        signal,
      });
      return {
        outcome: res.data ? toCheckInOutcome(res.data) : 'NOT_ALLOWED',
        reservationId,
        at: now(),
      };
    } catch (e) {
      // 형식 오류(400)는 단말기 화면에 보여 줄 결과다. 그 밖의 오류(연결 실패 등)는 그대로 던진다.
      if (isApiError(e) && e.kind === 'validation') {
        return { outcome: 'INVALID_QR', reservationId, at: now() };
      }
      throw e;
    }
  }

  return {
    checkIn: (reservationId, options) => scanRaw(toQrPayload(reservationId), options?.signal),
    scanRaw: (payload, options) => scanRaw(payload, options?.signal),
  };
}
