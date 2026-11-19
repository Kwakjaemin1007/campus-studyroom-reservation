import { describe, expect, it } from 'vitest';
import { checkParty, emptyRow } from './party';

describe('checkParty', () => {
  it('신청자 포함 2명 미만이면 오류', () => {
    expect(checkParty([emptyRow()], '202415175', 2, false).error).toMatch(/2명 이상/);
    expect(checkParty([{ studentId: '202415176', bid: '' }], '202415175', 2, false).error).toBeNull();
  });

  it('본인·중복·형식 오류는 그 줄에 표시하고 인원에서 뺀다', () => {
    const r = checkParty(
      [
        { studentId: '202415175', bid: '' },
        { studentId: '202415176', bid: '' },
        { studentId: '202415176', bid: '' },
        { studentId: 'abc', bid: '' },
      ],
      '202415175',
      2,
      false,
    );
    expect(r.rowErrors).toEqual(['본인은 이미 포함되어 있습니다.', null, '같은 학번이 두 번 들어갔습니다.', '학번은 숫자로 입력해 주세요.']);
    expect(r.companions).toEqual([{ studentId: '202415176', bid: 0 }]);
  });

  it('크레딧 베팅이면 동행 몫을 더한다', () => {
    const r = checkParty(
      [
        { studentId: '1001', bid: '20' },
        { studentId: '1002', bid: '' },
      ],
      '1000',
      2,
      true,
    );
    expect(r.companionBidTotal).toBe(20);
    expect(r.companions).toEqual([
      { studentId: '1001', bid: 20 },
      { studentId: '1002', bid: 0 },
    ]);
  });

  it('최소 인원을 모르면 2명으로 본다', () => {
    expect(checkParty([], 'x', null, false).error).toMatch(/2명 이상/);
  });
});
