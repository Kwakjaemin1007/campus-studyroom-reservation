import type { ReactNode } from 'react';

/** 정책 모듈들이 같이 쓰는 표시 조각. 신청서 안의 "항목 — 값" 한 줄이다. */
export function FactList({ children }: { children: ReactNode }) {
  return <dl className="divide-y divide-line rounded-md border border-line bg-white">{children}</dl>;
}

export function Fact({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 px-3 py-2">
      <dt className="shrink-0 text-xs text-sub">{term}</dt>
      <dd className="m-0 text-right text-sm text-ink">{children}</dd>
    </div>
  );
}

export function RuleNote({ children }: { children: ReactNode }) {
  return <p className="m-0 text-xs text-sub">{children}</p>;
}
