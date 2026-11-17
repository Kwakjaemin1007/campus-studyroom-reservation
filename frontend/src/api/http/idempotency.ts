import { uuid } from '@/lib/uuid';

/**
 * 멱등키 관리.
 *
 * 백엔드는 (userId, Idempotency-Key)로 요청 id 를 만들고 그 결과를 영구히 기억한다.
 * 그래서 프론트의 규칙은 두 가지다.
 *  1) 같은 "의도"(같은 사용자가 같은 슬롯을 신청)에는 같은 키를 쓴다.
 *     → 두 번 눌러도, 응답을 못 받아 다시 보내도 예약은 한 건이다.
 *  2) 그 의도가 끝나면(거절·취소·확실한 실패) 키를 버린다.
 *     → 다음 신청은 새 요청이 된다. 버리지 않으면 예전 거절 결과가 계속 돌아온다.
 *
 * 키는 sessionStorage 에도 적어 둔다. 응답을 기다리다 새로고침해도 같은 키로 이어서 보낸다.
 */
export interface KeyStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

interface Entry {
  key: string;
  ticketId?: string;
}

const STORAGE_KEY = 'studyroom.idempotency.v1';

export class IdempotencyKeyRing {
  private readonly entries = new Map<string, Entry>();

  constructor(
    private readonly storage: KeyStorage | null = null,
    private readonly newKey: () => string = uuid,
  ) {
    this.load();
  }

  /** 의도에 묶인 키를 돌려준다. 없으면 새로 만든다. */
  acquire(intent: string): string {
    const found = this.entries.get(intent);
    if (found) return found.key;
    const key = this.newKey();
    this.entries.set(intent, { key });
    this.save();
    return key;
  }

  peek(intent: string): string | null {
    return this.entries.get(intent)?.key ?? null;
  }

  /** 서버가 준 티켓 id 를 의도에 묶어 둔다. 나중에 티켓이 끝났을 때 키를 버리기 위해서다. */
  bindTicket(intent: string, ticketId: string): void {
    const found = this.entries.get(intent);
    if (!found || found.ticketId === ticketId) return;
    found.ticketId = ticketId;
    this.save();
  }

  release(intent: string): void {
    if (this.entries.delete(intent)) this.save();
  }

  releaseByTicket(ticketId: string): void {
    for (const [intent, entry] of this.entries) {
      if (entry.ticketId === ticketId) {
        this.entries.delete(intent);
        this.save();
        return;
      }
    }
  }

  private load(): void {
    if (!this.storage) return;
    try {
      const raw = this.storage.getItem(STORAGE_KEY);
      if (!raw) return;
      const parsed = JSON.parse(raw) as Record<string, Entry>;
      for (const [intent, entry] of Object.entries(parsed)) {
        if (entry && typeof entry.key === 'string') this.entries.set(intent, entry);
      }
    } catch {
      // 저장소를 못 쓰면 메모리만 쓴다.
    }
  }

  private save(): void {
    if (!this.storage) return;
    try {
      this.storage.setItem(STORAGE_KEY, JSON.stringify(Object.fromEntries(this.entries)));
    } catch {
      // 저장소를 못 쓰면 메모리만 쓴다.
    }
  }
}

/**
 * 같은 의도의 요청이 진행 중이면 새로 보내지 않고 진행 중인 것을 돌려준다.
 * 버튼을 연달아 눌러도 네트워크 요청은 한 번만 나간다.
 */
export class SingleFlight {
  private readonly inFlight = new Map<string, Promise<unknown>>();

  run<T>(intent: string, start: () => Promise<T>): Promise<T> {
    const running = this.inFlight.get(intent);
    if (running) return running as Promise<T>;
    const p = start().finally(() => {
      if (this.inFlight.get(intent) === p) this.inFlight.delete(intent);
    });
    this.inFlight.set(intent, p);
    return p;
  }

  isRunning(intent: string): boolean {
    return this.inFlight.has(intent);
  }
}

/** 브라우저 sessionStorage. 쓸 수 없는 환경이면 null */
export function browserSessionStorage(): KeyStorage | null {
  try {
    const s = globalThis.sessionStorage;
    const probe = '__studyroom_probe__';
    s.setItem(probe, '1');
    s.removeItem(probe);
    return s;
  } catch {
    return null;
  }
}
