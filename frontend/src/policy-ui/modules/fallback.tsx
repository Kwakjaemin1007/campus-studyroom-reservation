import { Fact, FactList } from '../parts';
import { definePolicyUI } from '../types';

/**
 * 대체 모듈. 두 경우에 쓰인다.
 *  1) 현재 정책을 알 수 없을 때 (GET /policy 가 없는 예전 서버이거나 조회에 실패했을 때)
 *  2) 백엔드에 새 정책이 등록됐는데 프론트 모듈이 아직 없을 때
 * 입력 없이 기본 신청서(bidAmount 0)를 보낸다. 정책이 입찰을 요구하면 서버가 NO_BID 로 거절한다.
 */
type FallbackDraft = Record<string, never>;

export const fallbackPolicyUI = definePolicyUI<FallbackDraft>({
  policy: '*',
  label: '기본 신청',
  timing: 'IMMEDIATE',
  rule: '현재 배정 방식에 따라 처리됩니다.',
  needs: [],

  createDraft: () => ({}),
  validate: () => ({}),
  toSubmission: () => ({ bidAmount: 0 }),

  submitLabel: () => '예약 신청',
  pendingCopy: () => ({
    title: '접수되었습니다',
    body: '현재 배정 방식에 따라 처리하고 있습니다. 결과가 나오면 이 화면에 표시됩니다.',
  }),
  reasonCopy: {
    NO_BID: '현재 배정 방식은 입찰이 필요한데, 입찰액 없이 신청되었습니다.',
  },

  Form: ({ ctx }) => (
    <div className="flex flex-col gap-2">
      <FactList>
        <Fact term="배정 방식">{ctx.policy ? ctx.policy.name : '확인할 수 없음'}</Fact>
      </FactList>
      <p className="banner-warn m-0">
        {ctx.policy
          ? `'${ctx.policy.name}' 배정 방식의 신청 화면이 아직 준비되지 않아 기본 신청서로 보냅니다.`
          : '현재 배정 방식을 확인할 수 없어 기본 신청서로 보냅니다. 입찰이 필요한 방식이면 거절될 수 있습니다.'}
      </p>
    </div>
  ),
});
