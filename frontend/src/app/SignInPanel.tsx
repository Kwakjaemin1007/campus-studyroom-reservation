import { useState, type FormEvent } from 'react';
import { adminToken, studentToken } from '@/auth/stubSsoSession';
import type { Session } from '@/domain/session';
import { session } from '@/services';

interface Props {
  readonly role: Session['role'];
  /** 다른 역할로 로그인되어 있으면 그 세션 */
  readonly current: Session | null;
}

/**
 * 로그인. 학사 SSO 는 외부 시스템이라 지금은 개발용 가짜 SSO(StubSsoSession)로 토큰을 만든다.
 * 실제 SSO 가 연동되면 이 양식 대신 SSO 로그인 화면으로 보낸다.
 */
export function SignInPanel({ role, current }: Props) {
  const [id, setId] = useState('');
  const [error, setError] = useState<string | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    const trimmed = id.trim();
    if (!trimmed || trimmed.includes(':')) {
      setError(role === 'admin' ? '관리자 아이디를 입력해 주세요.' : '학번을 입력해 주세요.');
      return;
    }
    try {
      session.signIn(role === 'admin' ? adminToken(trimmed) : studentToken(trimmed));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : '로그인하지 못했습니다.');
    }
  }

  return (
    <div className="mx-auto w-full max-w-[400px] px-4 py-10">
      <form className="surface flex flex-col gap-4 p-5" onSubmit={submit} noValidate>
        <div>
          <h1 className="m-0 text-md">{role === 'admin' ? '관리자 로그인' : '학생 로그인'}</h1>
          <p className="m-0 mt-1 text-xs text-sub">
            개발용 로그인입니다. 학사 SSO 연동 전이라 입력한 값으로 바로 로그인됩니다.
          </p>
        </div>

        {current && current.role !== role ? (
          <p className="banner-neutral m-0">
            지금은 {current.role === 'admin' ? '관리자' : '학생'}({current.userId})로 로그인되어 있습니다. 이 화면은{' '}
            {role === 'admin' ? '관리자' : '학생'} 계정이 필요합니다.
          </p>
        ) : null}

        <div>
          <label className="field-label" htmlFor="signin-id">
            {role === 'admin' ? '관리자 아이디' : '학번'}
          </label>
          <input
            id="signin-id"
            className="field-input num"
            value={id}
            onChange={(e) => setId(e.target.value)}
            autoComplete="username"
            spellCheck={false}
            inputMode={role === 'admin' ? 'text' : 'numeric'}
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? 'signin-error' : undefined}
          />
          {error ? (
            <p id="signin-error" className="field-error">
              {error}
            </p>
          ) : null}
        </div>

        <button type="submit" className="btn-primary h-11">
          로그인
        </button>
      </form>
    </div>
  );
}
