import { Fact, FactList, RuleNote } from '../parts';
import { definePolicyUI } from '../types';

/**
 * 우선순위(PriorityPolicy): 접수 창이 닫힌 뒤 우선순위가 가장 높은 신청이 확정된다.
 *
 * 우선순위 값은 학생이 입력하지 않는다. 백엔드가 SSO 토큰에서 읽는다(Identity.priority).
 * 그래서 이 신청서는 값을 보여 주기만 한다. 백엔드 신청 본문에 사유·학과 같은 필드가 생기면
 * 이 파일에 입력 칸을 추가하고 toSubmission 에 실으면 된다.
 */
type PriorityDraft = Record<string, never>;

export const priorityPolicyUI = definePolicyUI<PriorityDraft>({
  policy: 'PRIORITY',
  label: '우선순위',
  timing: 'AT_CLOSE',
  rule: '접수 마감 후 우선순위가 가장 높은 신청이 확정됩니다. 같으면 먼저 접수된 신청입니다.',
  needs: [],

  createDraft: () => ({}),
  validate: () => ({}),
  toSubmission: () => ({ bidAmount: 0 }),

  submitLabel: () => '우선순위로 신청',
  pendingCopy: (ctx) => ({
    title: '접수되었습니다',
    body: `이 시간의 첫 신청부터 ${ctx.collectWindowMinutes}분 동안 신청을 모은 뒤 한 번에 배정합니다.`,
  }),
  reasonCopy: {
    LOWER_PRIORITY: '우선순위가 더 높은 신청이 있었습니다.',
  },

  Form: ({ ctx }) => (
    <div className="flex flex-col gap-2">
      <FactList>
        <Fact term="배정 방식">우선순위</Fact>
        <Fact term="내 우선순위">
          <span className="num font-medium">{ctx.session.priority}</span>
        </Fact>
        <Fact term="결과 확인">
          접수 마감 후 (<span className="num">{ctx.collectWindowMinutes}</span>분 이내)
        </Fact>
      </FactList>
      <RuleNote>우선순위는 학사 로그인 정보로 정해지며 여기서 바꿀 수 없습니다. 숫자가 클수록 우선합니다.</RuleNote>
    </div>
  ),
});
