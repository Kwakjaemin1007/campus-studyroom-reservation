import { describe, expect, it } from 'vitest';
import { checkParty, emptyRow, notEnrolledIdOf, teamBid } from './party';

describe('checkParty', () => {
  it('신청자 포함 2명 미만이면 오류', () => {
    expect(checkParty([emptyRow()], '202415175', 2).error).toMatch(/2명 이상/);
    expect(checkParty([{ studentId: '202415176' }], '202415175', 2).error).toBeNull();
  });

  it('본인·중복·형식 오류는 그 줄에 표시하고 인원에서 뺀다', () => {
    const r = checkParty(
      [{ studentId: '202415175' }, { studentId: '202415176' }, { studentId: '202415176' }, { studentId: 'abc' }],
      '202415175',
      2,
    );
    expect(r.rowErrors).toEqual(['본인은 이미 포함되어 있습니다.', null, '같은 학번이 두 번 들어갔습니다.', '학번은 숫자로 입력해 주세요.']);
    expect(r.companions).toEqual([{ studentId: '202415176' }]);
    expect(r.size).toBe(2);
  });

  it('서버가 재학생이 아니라고 알려 준 학번은 그 줄에 표시하고, 고치면 사라진다', () => {
    const bad = new Set(['000123']);
    const r = checkParty([{ studentId: '000123' }, { studentId: '1002' }], '1000', 2, bad);
    expect(r.rowErrors[0]).toMatch(/재학생 명단에 없는/);
    expect(r.size).toBe(2);
    expect(checkParty([{ studentId: '1003' }], '1000', 2, bad).rowErrors).toEqual([null]);
  });

  it('최소 인원을 모르면 2명으로 본다', () => {
    expect(checkParty([], 'x', null).error).toMatch(/2명 이상/);
  });
});

describe('팀 입찰과 서버 오류', () => {
  it('팀 입찰액 = 1인당 × 인원', () => {
    expect(teamBid(20, 3)).toBe(60);
    expect(teamBid(0, 2)).toBe(0);
  });

  it('NOT_ENROLLED:학번 에서 학번을 꺼낸다', () => {
    expect(notEnrolledIdOf('NOT_ENROLLED:000123')).toBe('000123');
    expect(notEnrolledIdOf('duplicate member: 1')).toBeNull();
    expect(notEnrolledIdOf(null)).toBeNull();
  });
});
