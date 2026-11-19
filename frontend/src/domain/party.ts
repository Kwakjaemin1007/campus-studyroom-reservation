import type { Companion } from './reservation';

/**
 * 함께 이용하는 사람 규칙(11/19 회의). 서버가 최종 판정하고, 화면은 보내기 전에 같은 규칙으로 미리 알려 준다.
 *  - 신청자 포함 최소 인원(minPartySize, 기본 2명)
 *  - 같은 학번 중복, 자기 자신을 동행으로 넣기 금지
 *  - 동행 학번은 실제 재학생이어야 한다. 학사 명단은 서버만 볼 수 있어, 서버가 NOT_ENROLLED:학번 으로 알려 주면 그 칸에 표시한다
 *  - 같은 시간에 다른 예약에 들어가 있는지는 서버만 알 수 있다(MEMBER_TIME_CONFLICT)
 *  - 크레딧 베팅은 전원이 같은 1인당 금액을 건다. 팀 입찰액 = 1인당 × 인원
 */
export const DEFAULT_MIN_PARTY_SIZE = 2;

/** 서버 400 오류 문구의 앞부분: 재학생이 아닌 학번. 뒤에 ":학번"이 붙는다 */
export const NOT_ENROLLED_PREFIX = 'NOT_ENROLLED:';

/** 학번: 숫자 4~12자리. 데모·테스트 계정도 숫자만 쓴다. 실제 자릿수는 학사 SSO에 맞춰 바꾼다. */
const STUDENT_ID = /^\d{4,12}$/;

export interface PartyDraftRow {
  readonly studentId: string;
}

export interface PartyCheck {
  /** 줄마다의 오류(없으면 null) */
  readonly rowErrors: readonly (string | null)[];
  /** 전체 오류(인원 부족 등) */
  readonly error: string | null;
  readonly companions: readonly Companion[];
  /** 신청자 포함 인원 */
  readonly size: number;
}

export function emptyRow(): PartyDraftRow {
  return { studentId: '' };
}

/** 서버 오류 문구에서 재학생이 아닌 학번을 꺼낸다. 해당 없으면 null */
export function notEnrolledIdOf(serverMessage: string | null | undefined): string | null {
  if (!serverMessage || !serverMessage.startsWith(NOT_ENROLLED_PREFIX)) return null;
  const id = serverMessage.slice(NOT_ENROLLED_PREFIX.length).trim();
  return id === '' ? null : id;
}

/** 팀 입찰액 = 1인당 입찰액 × 인원 */
export function teamBid(perPerson: number, size: number): number {
  return perPerson * size;
}

export function checkParty(
  rows: readonly PartyDraftRow[],
  selfId: string | null,
  minPartySize: number | null,
  notEnrolled: ReadonlySet<string> = new Set(),
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
    else if (notEnrolled.has(id)) err = '재학생 명단에 없는 학번입니다. 휴학·졸업생은 함께 신청할 수 없습니다.';
    rowErrors.push(err);
    if (!err) {
      seen.add(id);
      companions.push({ studentId: id });
    }
  }
  const size = 1 + companions.length;
  const error =
    size < min ? `신청자를 포함해 ${min}명 이상이어야 합니다. 함께 이용할 사람의 학번을 넣어 주세요.` : null;
  return { rowErrors, error, companions, size };
}
