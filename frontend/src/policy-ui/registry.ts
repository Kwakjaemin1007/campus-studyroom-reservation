import type { PolicyName } from '@/domain/policy';
import { COMMON_REASON_COPY } from '@/domain/reservation';
import type { AnyPolicyUIModule } from './types';

/**
 * 정책 이름 → UI 모듈 (프론트의 Registry). 백엔드 PolicyRegistry 와 짝을 이룬다.
 *
 * 화면 코드에는 `if (policy === 'CREDIT_BETTING')` 같은 분기를 두지 않는다.
 * 드로어는 resolve(이름)으로 받은 모듈의 Form·validate·toSubmission 을 부를 뿐이다.
 */
export interface ResolvedPolicyUI {
  readonly module: AnyPolicyUIModule;
  /** 이름에 맞는 모듈을 찾았으면 true. false 면 대체 모듈이다. */
  readonly matched: boolean;
}

export class PolicyUIRegistry {
  private readonly modules = new Map<string, AnyPolicyUIModule>();

  /** @param fallback 정책을 알 수 없거나 등록되지 않은 정책일 때 쓰는 모듈 */
  constructor(private readonly fallback: AnyPolicyUIModule) {}

  register(module: AnyPolicyUIModule): this {
    if (this.modules.has(module.policy)) {
      throw new Error(`정책 UI 모듈이 이미 등록되어 있습니다: ${module.policy}`);
    }
    this.modules.set(module.policy, module);
    return this;
  }

  has(name: PolicyName): boolean {
    return this.modules.has(name);
  }

  /** 등록된 정책 이름(등록 순서) */
  names(): PolicyName[] {
    return [...this.modules.keys()];
  }

  list(): AnyPolicyUIModule[] {
    return [...this.modules.values()];
  }

  resolve(name: PolicyName | null | undefined): ResolvedPolicyUI {
    const found = name ? this.modules.get(name) : undefined;
    return found ? { module: found, matched: true } : { module: this.fallback, matched: false };
  }

  /** 화면에 보이는 정책 이름. 모르는 정책은 이름 그대로 */
  labelOf(name: PolicyName): string {
    return this.modules.get(name)?.label ?? name;
  }

  /**
   * 거절·취소 사유 코드의 문구.
   * 정책을 알면 그 모듈의 문구를 먼저 보고, 없으면 등록된 모든 모듈과 공통 문구에서 찾는다.
   * (끝난 예약은 지금 정책이 아니라 예전 정책으로 판정됐을 수 있다.)
   */
  reasonText(code: string | null, policy?: PolicyName | null): string {
    if (!code) return '';
    const own = policy ? this.modules.get(policy)?.reasonCopy[code] : undefined;
    if (own) return own;
    for (const m of this.modules.values()) {
      const text = m.reasonCopy[code];
      if (text) return text;
    }
    return COMMON_REASON_COPY[code] ?? this.fallback.reasonCopy[code] ?? `사유: ${code}`;
  }
}
