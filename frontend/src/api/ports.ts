import type { Capability } from '@/domain/capability';
import type { ActivePolicy, PolicyName, PolicyStatus, SwitchProgress } from '@/domain/policy';
import type {
  CheckInResult,
  CreditBalance,
  QueueLoad,
  ReservationDetail,
  ReserveInput,
  SlotRef,
  TicketView,
} from '@/domain/reservation';

/**
 * 화면이 의존하는 창구(포트). 화면은 이 인터페이스와 domain/ 모델만 안다.
 * 백엔드 경로·DTO·헤더는 api/adapters/ 안에서 끝난다.
 *
 * 백엔드가 Adapter 로 SSO·알림·QR 기기를 가린 것과 같은 방식으로, 프론트는 백엔드 HTTP 를 가린다.
 */

export interface RequestOptions {
  readonly signal?: AbortSignal;
}

/** 학생 예약. 백엔드: ReservationController */
export interface ReservationGateway {
  /**
   * 예약을 신청하고 번호표를 받는다. Idempotency-Key 는 어댑터가 붙인다.
   * 같은 슬롯을 다시 신청하면(연타·재전송) 같은 번호표가 돌아온다.
   * @throws ApiError slot_closed(409) · rate_limited(429, 자동 재시도 후에도 실패) · unavailable(503) · validation · unauthorized
   */
  reserve(input: ReserveInput, options?: RequestOptions & { onRetry?: (delayMs: number) => void }): Promise<TicketView>;

  getTicket(ticketId: string, options?: RequestOptions): Promise<TicketView>;

  /**
   * 예약 상세. 아직 큐에서 기다리는 요청이거나(저장 전) 서버에 이 조회가 없으면 null.
   * @throws ApiError forbidden(남의 예약)
   */
  getDetail(reservationId: string, options?: RequestOptions): Promise<ReservationDetail | null>;

  /** @throws ApiError not_cancelable(409) · forbidden · not_found */
  cancel(reservationId: string, options?: RequestOptions): Promise<void>;
}

/** 관리자 정책 제어. 백엔드: AdminPolicyController */
export interface AdminPolicyGateway {
  getActivePolicy(options?: RequestOptions): Promise<ActivePolicy>;

  /** @throws ApiError switch_conflict(409) · validation(모르는 정책 이름) · forbidden */
  requestSwitch(target: PolicyName, options?: RequestOptions): Promise<{ switchId: string }>;

  getSwitchProgress(switchId: string, options?: RequestOptions): Promise<SwitchProgress>;
}

/** 현장 QR 단말기. 백엔드: QrDeviceController */
export interface QrDeviceGateway {
  /** 예약 id 를 기기 형식("RSV:{id}")으로 감싸 보낸다. 실패도 예외가 아니라 결과로 돌려준다. */
  checkIn(reservationId: string, options?: RequestOptions): Promise<CheckInResult>;

  /** 스캐너가 읽은 문자열을 그대로 보낸다. */
  scanRaw(payload: string, options?: RequestOptions): Promise<CheckInResult>;
}

/*
 * ───────── 조회 창구. 백엔드: QueryController ─────────
 * 조회 API 가 없는 예전 서버에 붙어도 화면이 죽지 않도록 결과를 Capability 로 준다(404 → unsupported).
 */

/** 학생이 읽는 현재 정책·전환 상태 */
export interface PolicyStatusGateway {
  getStatus(options?: RequestOptions): Promise<Capability<PolicyStatus>>;
}

export interface SlotBoardQuery {
  readonly roomIds: readonly string[];
  /** 포함 */
  readonly from: Date;
  /** 제외 */
  readonly to: Date;
}

/** 타임테이블 점유 현황 */
export interface SlotBoardGateway {
  /** 기간 안에서 지금 차 있는 슬롯의 키(slotKey) 집합. 여기 없는 슬롯은 비어 있다. */
  getTaken(query: SlotBoardQuery, options?: RequestOptions): Promise<Capability<ReadonlySet<string>>>;
}

/** 내 크레딧 잔액 */
export interface CreditGateway {
  getBalance(options?: RequestOptions): Promise<Capability<CreditBalance>>;
}

/** 접수 대기열 부하 */
export interface QueueGateway {
  getLoad(options?: RequestOptions): Promise<Capability<QueueLoad>>;
}

export interface Api {
  readonly reservations: ReservationGateway;
  readonly adminPolicy: AdminPolicyGateway;
  readonly qrDevice: QrDeviceGateway;
  readonly policyStatus: PolicyStatusGateway;
  readonly slotBoard: SlotBoardGateway;
  readonly credits: CreditGateway;
  readonly queue: QueueGateway;
}

export type { SlotRef };
