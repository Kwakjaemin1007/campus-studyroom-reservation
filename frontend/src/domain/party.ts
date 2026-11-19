import type { Companion } from './reservation';

/**
 * 함께 이용하는 사람 규칙(11/19 회의). 서버가 최종 판정하고, 화면은 보내기 전에 같은 규칙으로 미리 알려 준다.
 *  - 신청자 포함 최소 인원(minPartySize, 기본 2명)
 *  - 같은 학번 중복, 자기 자신을 동행으로 넣기 금지
 *  - 같은 시간에 다른 예약에 들어가 있는지는 서버만 알 수 있다(MEMBER_TIME_CONFLICT)
 */
export const DEFAULT_MIN_PARTY_SIZE = 2;

/** 학번: 숫자 4~12자리. 데모·테스트 계정도 숫자만 쓴다. 실제 자릿수는 학사 SSO에 맞춰 바꾼다. */
const STUDENT_ID = /^\d{4,12}$/;

export interface PartyDraftRow {
  readonly studentId: string;
  /** 입력 그대로(빈 칸 허용). 크레딧 베팅일 때만 쓴다 */
  readonly bid: string;
}

export interface PartyCheck {
  /** 줄마다의 오류(없으면 null) */
  readonly rowErrors: readonly (string | null)[];
  /** 전체 오류(인원 부족 등) */
  readonly error: string | null;
  readonly companions: readonly Companion[];
  /** 동행들이 건 크레딧 합(신청자 몫 제외) */
  readonly companionBidTotal: number;
}

export function emptyRow(): PartyDraftRow {
  return { studentId: '', bid: '' };
}

export function checkParty(
  rows: readonly PartyDraftRow[],
  selfId: string | null,
  minPartySize: number | null,
  withBids: boolean,
): PartyCheck {
  const min = minPartySize ?? DEFAULT_MIN_PARTY_SIZE;
  const seen = new Set<string>(selfId ? [selfId] : []);
  const rowErrors: (string | null)[] = [];
  const companions: Companion[] = [];
  for (const row of rows) {
    const id = row.studentId.trim();
    if (id === '') {
      rowErrors.push(null);
      continue;
    }
    let err: string | null = null;
    if (!STUDENT_ID.test(id)) err = '학번은 숫자로 입력해 주세요.';
    else if (id === selfId) err = '본인은 이미 포함되어 있습니다.';
    else if (seen.has(id)) err = '같은 학번이 두 번 들어갔습니다.';
    let bid = 0;
    if (!err && withBids && row.bid.trim() !== '') {
      const n = Number(row.bid);
      if (!Number.isInteger(n) || n < 0) err = '크레딧은 0 이상의 정수로 입력해 주세요.';
      else bid = n;
    }
    rowErrors.push(err);
    if (!err) {
      seen.add(id);
      companions.push({ studentId: id, bid });
    }
  }
  const people = 1 + companions.length;
  const error =
    people < min ? `신청자를 포함해 ${min}명 이상이어야 합니다. 함께 이용할 사람의 학번을 넣어 주세요.` : null;
  return { rowErrors, error, companions, companionBidTotal: companions.reduce((a, c) => a + c.bid, 0) };
}
