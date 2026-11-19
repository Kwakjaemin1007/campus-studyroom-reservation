import type { CreditBalanceDto, PolicyStatusDto, QueueDto, SlotStateDto } from '@/api/dto/backend';
import type { HttpClient } from '@/api/http/httpClient';
import type {
  AdminPolicyGateway,
  CreditGateway,
  PolicyStatusGateway,
  QueueGateway,
  SlotBoardGateway,
} from '@/api/ports';
import { supported, unsupported, type Capability } from '@/domain/capability';
import { ApiError, isApiError } from '@/domain/errors';
import { toCreditBalance, toPolicyStatus, toQueueLoad, toTakenSlotKey } from './mappers';

/**
 * 조회 API 어댑터 (백엔드 QueryController).
 *
 *   GET /policy                         → { name, version, timing, phase, graceSeconds, collectWindowSeconds }
 *   GET /slots?roomId=A,B&from=&to=     → [{ roomId, startsAt, taken: true }]  (차 있는 슬롯만)
 *   GET /credits/me                     → { balance, held }
 *   GET /queue                          → { depth }
 *
 * 이 경로가 없는 예전 서버에서는 404 가 온다. 그때는 값을 지어내지 않고 unsupported 를 돌려준다.
 * 경로나 응답 모양이 바뀌면 이 파일과 dto/backend.ts, mappers.ts 만 고친다.
 */

const MISSING = '서버에 이 조회 API가 없습니다.';

/** 없는 경로(404·405)면 unsupported, 그 밖의 오류는 그대로 던진다. */
async function probe<T>(run: () => Promise<T>): Promise<Capability<T>> {
  try {
    return supported(await run());
  } catch (e) {
    if (isApiError(e) && e.kind === 'not_found') return unsupported(MISSING);
    throw e;
  }
}

function requireBody<T>(data: T | null): T {
  if (data === null || data === undefined) throw new ApiError({ kind: 'server', serverMessage: '응답이 비어 있습니다.' });
  return data;
}

export interface PolicyStatusAdapterDeps {
  readonly http: HttpClient;
  /** 학생용 경로가 없는 서버에서, 관리자 세션이면 GET /admin/policy 로 대신 읽는다 */
  readonly adminPolicy: AdminPolicyGateway;
  readonly isAdmin: () => boolean;
}

export function createPolicyStatusAdapter(deps: PolicyStatusAdapterDeps): PolicyStatusGateway {
  return {
    async getStatus(options) {
      const result = await probe(async () => {
        const res = await deps.http.request<PolicyStatusDto>({
          method: 'GET',
          path: '/policy',
          signal: options?.signal,
        });
        return toPolicyStatus(requireBody(res.data));
      });
      if (result.supported || !deps.isAdmin()) return result;

      const policy = await deps.adminPolicy.getActivePolicy(options);
      return supported({ policy, timing: null, phase: null, graceSeconds: null, collectWindowSeconds: null, maxSlotsPerDay: null, minPartySize: null });
    },
  };
}

export function createSlotBoardAdapter(http: HttpClient): SlotBoardGateway {
  return {
    getTaken(query, options) {
      return probe(async () => {
        const res = await http.request<SlotStateDto[]>({
          method: 'GET',
          path: '/slots',
          query: {
            roomId: query.roomIds.join(','),
            from: query.from.toISOString(),
            to: query.to.toISOString(),
          },
          signal: options?.signal,
        });
        const taken = new Set<string>();
        for (const dto of res.data ?? []) {
          const key = toTakenSlotKey(dto);
          if (key) taken.add(key);
        }
        return taken as ReadonlySet<string>;
      });
    },
  };
}

export function createCreditAdapter(http: HttpClient): CreditGateway {
  return {
    getBalance(options) {
      return probe(async () => {
        const res = await http.request<CreditBalanceDto>({
          method: 'GET',
          path: '/credits/me',
          signal: options?.signal,
        });
        return toCreditBalance(requireBody(res.data));
      });
    },
  };
}

export function createQueueAdapter(http: HttpClient): QueueGateway {
  return {
    getLoad(options) {
      return probe(async () => {
        const res = await http.request<QueueDto>({ method: 'GET', path: '/queue', signal: options?.signal });
        return toQueueLoad(requireBody(res.data));
      });
    },
  };
}
