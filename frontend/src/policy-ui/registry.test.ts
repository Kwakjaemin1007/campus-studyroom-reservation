import { describe, expect, it } from 'vitest';
import { supported } from '@/domain/capability';
import { policyUIRegistry, PolicyUIRegistry, type PolicyFormContext } from '@/policy-ui';
import { fallbackPolicyUI } from './modules/fallback';
import { fcfsPolicyUI } from './modules/fcfs';

const ctx: PolicyFormContext = {
  slot: { roomId: 'ENG-KC-1', startsAt: new Date('2026-11-20T05:00:00Z') },
  session: { token: 'user:u-101:2', userId: 'u-101', priority: 2, role: 'student' },
  policy: { name: 'CREDIT_BETTING', version: 2 },
  credits: supported({ available: 80, held: 20, renewsAt: null }),
  collectWindowMinutes: 10,
};

describe('PolicyUIRegistry', () => {
  it('백엔드에 등록된 세 정책의 모듈을 이름으로 찾는다', () => {
    expect(policyUIRegistry.names()).toEqual(['FCFS', 'PRIORITY', 'CREDIT_BETTING']);
    for (const name of policyUIRegistry.names()) {
      const { module, matched } = policyUIRegistry.resolve(name);
      expect(matched).toBe(true);
      expect(module.policy).toBe(name);
    }
  });

  it('모르는 정책과 알 수 없는 정책은 대체 모듈로 받는다', () => {
    expect(policyUIRegistry.resolve('LOTTERY')).toEqual({ module: fallbackPolicyUI, matched: false });
    expect(policyUIRegistry.resolve(null).matched).toBe(false);
  });

  it('같은 정책을 두 번 등록하면 막는다', () => {
    const registry = new PolicyUIRegistry(fallbackPolicyUI).register(fcfsPolicyUI);
    expect(() => registry.register(fcfsPolicyUI)).toThrow();
  });

  it('새 정책은 register 한 줄로 추가된다', () => {
    const registry = new PolicyUIRegistry(fallbackPolicyUI).register({ ...fcfsPolicyUI, policy: 'LOTTERY', label: '추첨' });
    expect(registry.resolve('LOTTERY').matched).toBe(true);
    expect(registry.labelOf('LOTTERY')).toBe('추첨');
  });

  it('사유 문구는 정책 모듈, 다른 모듈, 공통 문구 순으로 찾는다', () => {
    expect(policyUIRegistry.reasonText('OUTBID', 'CREDIT_BETTING')).toContain('더 높은 입찰');
    expect(policyUIRegistry.reasonText('LOST_FCFS', 'CREDIT_BETTING')).toContain('먼저 접수');
    expect(policyUIRegistry.reasonText('NO_SHOW')).toContain('입실하지 않아');
    expect(policyUIRegistry.reasonText('SOMETHING_NEW')).toBe('사유: SOMETHING_NEW');
  });
});

describe('정책 모듈이 서버로 보내는 값', () => {
  it('선착순과 우선순위는 입찰액 0 을 보낸다', () => {
    for (const name of ['FCFS', 'PRIORITY']) {
      const { module } = policyUIRegistry.resolve(name);
      const draft: unknown = module.createDraft(ctx);
      expect(module.validate(draft, ctx)).toEqual({});
      expect(module.toSubmission(draft, ctx)).toEqual({ bidAmount: 0 });
    }
  });

  it('크레딧 베팅은 1 이상의 정수이고 보유 크레딧 이하일 때만 통과한다', () => {
    const { module } = policyUIRegistry.resolve('CREDIT_BETTING');
    expect(module.validate({ bid: '35' }, ctx)).toEqual({});
    expect(module.toSubmission({ bid: '35' }, ctx)).toEqual({ bidAmount: 35 });
    expect(module.validate({ bid: '' }, ctx)).toHaveProperty('bid');
    expect(module.validate({ bid: '0' }, ctx)).toHaveProperty('bid');
    expect(module.validate({ bid: '81' }, ctx)).toHaveProperty('bid');
    expect(module.submitLabel({ bid: '35' }, ctx)).toBe('35크레딧으로 입찰');
  });

  it('보유 크레딧을 모르면 상한 검사를 건너뛴다 (서버가 INSUFFICIENT_CREDIT 로 판정)', () => {
    const { module } = policyUIRegistry.resolve('CREDIT_BETTING');
    const unknown: PolicyFormContext = { ...ctx, credits: { supported: false, reason: '없음' } };
    expect(module.validate({ bid: '500' }, unknown)).toEqual({});
  });
});
