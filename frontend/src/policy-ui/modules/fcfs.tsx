import { Fact, FactList, RuleNote } from '../parts';
import { definePolicyUI } from '../types';

/** 선착순(FcfsPolicy): 입력할 것이 없다. 신청하면 바로 판정된다. */
type FcfsDraft = Record<string, never>;

export const fcfsPolicyUI = definePolicyUI<FcfsDraft>({
  policy: 'FCFS',
  label: '선착순',
  timing: 'IMMEDIATE',
  rule: '먼저 접수된 신청 한 건이 확정됩니다.',
  needs: [],

  createDraft: () => ({}),
  validate: () => ({}),
  toSubmission: () => ({ bidAmount: 0 }),

  submitLabel: () => '예약 신청',
  pendingCopy: () => ({
    title: '접수되었습니다',
    body: '대기열에서 순서대로 처리하고 있습니다. 결과는 이 화면에 바로 표시됩니다.',
  }),
  reasonCopy: {
    LOST_FCFS: '같은 시간에 먼저 접수된 신청이 있었습니다.',
  },

  Form: () => (
    <div className="flex flex-col gap-2">
      <FactList>
        <Fact term="배정 방식">선착순</Fact>
        <Fact term="결과 확인">신청 직후</Fact>
      </FactList>
      <RuleNote>추가로 입력할 내용이 없습니다. 신청 순서는 서버에 접수된 순서로 정해집니다.</RuleNote>
    </div>
  ),
});
