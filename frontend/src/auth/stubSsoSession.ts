import type { KeyStorage } from '@/api/http/idempotency';
import type { Session } from '@/domain/session';
import type { SessionPort } from './sessionPort';

/**
 * 개발용 가짜 SSO. 백엔드 StubSsoAuthAdapter 와 같은 토큰 형식을 쓴다.
 *   user:{학번}            → 학생, 우선순위 0
 *   user:{학번}:{우선순위}  → 학생
 *   admin:{id}             → 관리자
 * 실제 SSO 에서는 토큰이 서명된 JWT 이고, 이 파일 대신 학교 SSO 용 구현을 꽂는다.
 */
export function parseStubToken(token: string): Session | null {
  const p = token.trim().split(':');
  const id = p[1];
  if (p[0] === 'user' && p.length >= 2 && id && id.trim() !== '') {
    const priority = p.length >= 3 ? Number(p[2]) : 0;
    if (!Number.isInteger(priority)) return null;
    return { token: token.trim(), userId: id, priority, role: 'student' };
  }
  if (p[0] === 'admin' && p.length === 2 && id && id.trim() !== '') {
    return { token: token.trim(), userId: id, priority: 0, role: 'admin' };
  }
  return null;
}

export function studentToken(userId: string, priority = 0): string {
  return `user:${userId}:${priority}`;
}

export function adminToken(adminId: string): string {
  return `admin:${adminId}`;
}

const STORAGE_KEY = 'studyroom.session.v1';

export class StubSsoSession implements SessionPort {
  private session: Session | null = null;
  private readonly listeners = new Set<(session: Session | null) => void>();

  constructor(private readonly storage: KeyStorage | null = null) {
    try {
      const saved = storage?.getItem(STORAGE_KEY);
      if (saved) this.session = parseStubToken(saved);
    } catch {
      // 저장소를 못 쓰면 로그인 상태를 기억하지 않는다.
    }
  }

  current(): Session | null {
    return this.session;
  }

  signIn(token: string): Session {
    const session = parseStubToken(token);
    if (!session) throw new Error('토큰 형식이 올바르지 않습니다. 예: user:202415175:1 또는 admin:a1');
    this.session = session;
    try {
      this.storage?.setItem(STORAGE_KEY, session.token);
    } catch {
      // 무시
    }
    this.emit();
    return session;
  }

  signOut(): void {
    this.session = null;
    try {
      this.storage?.removeItem(STORAGE_KEY);
    } catch {
      // 무시
    }
    this.emit();
  }

  subscribe(listener: (session: Session | null) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private emit(): void {
    for (const l of this.listeners) l(this.session);
  }
}
