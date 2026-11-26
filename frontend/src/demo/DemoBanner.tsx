import { useState } from 'react';
import { demoBackend } from '@/services';
import { DEFAULT_DEMO_OPTIONS } from './demoServer';

/**
 * 데모 빌드임을 알리는 띠. 진짜 서버에 붙는 빌드에서는 아무것도 그리지 않는다.
 */
export function DemoBanner() {
  const [open, setOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  if (!demoBackend) return null;
  const backend = demoBackend;

  function reset() {
    backend.reset();
    try {
      for (const key of Object.keys(window.localStorage)) {
        if (key.startsWith('studyroom.tickets.')) window.localStorage.removeItem(key);
      }
    } catch {
      // 저장소를 못 쓰면 지울 것도 없다.
    }
    window.location.reload();
  }

  return (
    <div className="hairline-b bg-canvas px-4 py-2 text-xs text-sub lg:px-8">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span>
          <strong className="font-semibold text-ink">데모 모드</strong> · 서버 없이 브라우저 안에서 백엔드 규칙대로
          동작합니다. 데이터는 이 브라우저에만 저장됩니다.
        </span>
        <button type="button" className="btn-secondary h-7 px-2 text-xs" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
          {open ? '설명 닫기' : '써 보는 법'}
        </button>
        {confirming ? (
          <span className="flex items-center gap-2">
            <span className="text-ink">이 브라우저의 데모 예약·크레딧·정책을 모두 지울까요?</span>
            <button type="button" className="btn-danger h-7 px-2 text-xs" onClick={reset}>
              지우기
            </button>
            <button type="button" className="btn-secondary h-7 px-2 text-xs" onClick={() => setConfirming(false)}>
              그만두기
            </button>
          </span>
        ) : (
          <button type="button" className="btn-secondary h-7 px-2 text-xs" onClick={() => setConfirming(true)}>
            데모 데이터 초기화
          </button>
        )}
      </div>
      {open ? (
        <ul className="m-0 mt-2 flex list-disc flex-col gap-1 pl-4">
          <li>학번은 아무 숫자나 넣으면 됩니다. 탭마다 다른 사람으로 로그인할 수 있어서, 탭 두 개로 같은 칸을 다투어 볼 수 있습니다.</li>
          <li>관리자 탭에서 아무 아이디로 로그인해 정책을 바꾸면, 열려 있는 학생 화면의 신청서가 그 정책에 맞게 바뀝니다.</li>
          <li>
            크레딧 베팅은 그 칸의 첫 신청부터 {DEFAULT_DEMO_OPTIONS.collectWindowSeconds}초 동안 신청을 모은 뒤 한 번에
            배정합니다(실제 설정은 10분). 크레딧은 {DEFAULT_DEMO_OPTIONS.initialCredits}씩 지급됩니다.
          </li>
          <li>확정된 예약의 "입실 코드 보기"에서 키오스크를 열어 입실해 볼 수 있습니다. 시작 후 10분 안에 입실하지 않으면 자동 해제됩니다.</li>
        </ul>
      ) : null}
    </div>
  );
}
