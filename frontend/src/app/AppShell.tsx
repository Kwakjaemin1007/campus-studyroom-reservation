import type { ReactNode } from 'react';
import type { Session } from '@/domain/session';
import { DemoBanner } from '@/demo/DemoBanner';
import { session as sessionPort } from '@/services';
import { hrefOf } from './useHashRoute';

export interface NavItem {
  readonly path: string;
  readonly label: string;
}

interface Props {
  readonly nav: readonly NavItem[];
  readonly activePath: string;
  readonly session: Session | null;
  /** 머리줄 오른쪽에 화면이 끼워 넣는 요약(크레딧 등) */
  readonly children: ReactNode;
}

/** 머리줄과 화면 전환 탭. 활성 탭 표시가 KU 그린을 쓰는 세 곳 중 하나다. */
export function AppShell({ nav, activePath, session, children }: Props) {
  return (
    <div className="flex min-h-full flex-col">
      <header className="hairline-b bg-white">
        <div className="flex min-h-[56px] flex-wrap items-center gap-x-6 gap-y-1 px-4 lg:px-8">
          <div className="text-md font-semibold">스터디룸 예약</div>
          <nav className="flex h-14 gap-5" aria-label="화면">
            {nav.map((item) => {
              const active = item.path === activePath;
              return (
                <a
                  key={item.path}
                  href={hrefOf(item.path)}
                  aria-current={active ? 'page' : undefined}
                  className={`flex items-center border-b-2 text-base no-underline ${
                    active ? 'border-ku font-semibold text-ink' : 'border-transparent text-sub hover:text-ink'
                  }`}
                >
                  {item.label}
                </a>
              );
            })}
          </nav>
          {session ? (
            <div className="ml-auto flex items-center gap-3 py-2 text-sm text-sub">
              <span>
                <span className="num text-ink">{session.userId}</span>
                {session.role === 'admin' ? ' · 관리자' : ''}
              </span>
              <button type="button" className="btn-secondary h-8 px-2 text-xs" onClick={() => sessionPort.signOut()}>
                로그아웃
              </button>
            </div>
          ) : null}
        </div>
      </header>
      <DemoBanner />
      <div className="flex min-h-0 flex-1 flex-col">{children}</div>
    </div>
  );
}
