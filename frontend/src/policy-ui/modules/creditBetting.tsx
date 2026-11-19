import { Fact, FactList, RuleNote } from '../parts';
import { definePolicyUI, type FieldErrors, type PolicyFormContext } from '../types';

/**
 * 크레딧 베팅(CreditBettingPolicy): 접수 창이 닫힌 뒤 가장 많이 건 신청이 확정된다.
 * 규칙: 정수 1크레딧 단위, 비공개 입찰, 동점은 먼저 입찰한 사람, 탈락하면 환불.
 */
interface BettingDraft {
  /** 입력 중인 글자 그대로. 비어 있을 수 있다 */
  readonly bid: string;
}

const MIN_BID = 1;

/** 잔액을 알면 그 값, 모르면 null */
function availableOf(ctx: PolicyFormContext): number | null {
  return ctx.credits?.supported ? ctx.credits.value.available : null;
}

function parseBid(raw: string): number | null {
  if (!/^\d+$/.test(raw.trim())) return null;
  return Number(raw.trim());
}

export const creditBettingPolicyUI = definePolicyUI<BettingDraft>({
  policy: 'CREDIT_BETTING',
  label: '크레딧 베팅',
  timing: 'AT_CLOSE',
  rule: '접수 마감 후 가장 많이 건 신청이 확정됩니다. 같으면 먼저 입찰한 신청입니다.',
  needs: ['credits'],

  createDraft: () => ({ bid: String(MIN_BID) }),

  validate(draft, ctx): FieldErrors {
    const bid = parseBid(draft.bid);
    if (bid === null) return { bid: '입찰액은 1 이상의 정수로 입력해 주세요.' };
    if (bid < MIN_BID) return { bid: `최소 입찰액은 ${MIN_BID}크레딧입니다.` };
    const available = availableOf(ctx);
    if (available !== null && bid > available) {
      return { bid: `보유 크레딧(${available})보다 많이 걸 수 없습니다.` };
    }
    return {};
  },

  toSubmission: (draft) => ({ bidAmount: parseBid(draft.bid) ?? 0 }),

  submitLabel: (draft) => {
    const bid = parseBid(draft.bid);
    return bid === null ? '입찰' : `${bid}크레딧으로 입찰`;
  },

  pendingCopy: (ctx) => ({
    title: '입찰이 접수되었습니다',
    body: `이 시간의 첫 입찰부터 ${ctx.collectWindowMinutes}분 동안 입찰을 모은 뒤 한 번에 배정합니다. 다른 사람의 입찰액은 공개되지 않습니다.`,
  }),

  reasonCopy: {
    OUTBID: '더 높은 입찰이 있었습니다. 묶여 있던 크레딧은 돌려드렸습니다.',
    NO_BID: '입찰액 없이 신청되어 배정 대상에서 빠졌습니다.',
    INSUFFICIENT_CREDIT: '보유 크레딧이 입찰액보다 적었습니다.',
  },

  Form: ({ draft, onChange, errors, ctx, disabled }) => {
    const available = availableOf(ctx);
    const loading = ctx.credits === null;
    const bid = parseBid(draft.bid);
    const sliderMax = available !== null ? Math.max(available, MIN_BID) : null;
    const noCredit = available !== null && available < MIN_BID;

    return (
      <div className="flex flex-col gap-3">
        <FactList>
          <Fact term="배정 방식">크레딧 베팅 (비공개 입찰)</Fact>
          <Fact term="보유 크레딧">
            {loading ? (
              <span className="skeleton inline-block h-4 w-12 align-middle" aria-label="불러오는 중" />
            ) : available !== null ? (
              <span className="num font-medium">{available}</span>
            ) : (
              <span className="text-sub">확인할 수 없음</span>
            )}
          </Fact>
          {ctx.credits?.supported && ctx.credits.value.held > 0 ? (
            <Fact term="다른 입찰에 묶인 크레딧">
              <span className="num">{ctx.credits.value.held}</span>
            </Fact>
          ) : null}
          {ctx.credits?.supported && ctx.credits.value.renewsAt ? (
            <Fact term="크레딧 갱신">
              <span className="num">{ctx.credits.value.renewsAt.toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul' })}</span>
            </Fact>
          ) : null}
          <Fact term="결과 확인">
            접수 마감 후 (<span className="num">{ctx.collectWindowMinutes}</span>분 이내)
          </Fact>
        </FactList>

        <div>
          <label className="field-label" htmlFor="bet-amount">
            1인당 입찰액 (크레딧)
          </label>
          <div className="flex items-center gap-3">
            {sliderMax !== null ? (
              <input
                id="bet-range"
                className="range flex-1"
                type="range"
                min={MIN_BID}
                max={sliderMax}
                step={1}
                value={bid !== null ? Math.min(Math.max(bid, MIN_BID), sliderMax) : MIN_BID}
                onChange={(e) => onChange({ bid: e.target.value })}
                disabled={disabled || noCredit}
                aria-label="입찰액 슬라이더"
              />
            ) : null}
            <input
              id="bet-amount"
              className="field-input num w-24 text-right"
              type="text"
              inputMode="numeric"
              autoComplete="off"
              value={draft.bid}
              onChange={(e) => onChange({ bid: e.target.value.replace(/[^\d]/g, '') })}
              disabled={disabled || noCredit}
              aria-invalid={errors.bid ? true : undefined}
              aria-describedby={errors.bid ? 'bet-amount-error' : 'bet-amount-hint'}
            />
          </div>
          {sliderMax !== null ? (
            <div className="num mt-1 flex justify-between text-2xs text-sub" aria-hidden="true">
              <span>{MIN_BID}</span>
              <span>{sliderMax}</span>
            </div>
          ) : null}
          {errors.bid ? (
            <p id="bet-amount-error" className="field-error">
              {errors.bid}
            </p>
          ) : (
            <p id="bet-amount-hint" className="field-hint">
              함께 이용하는 사람 모두가 이 금액을 똑같이 겁니다. 입찰하는 동안 각자 이 크레딧이 묶입니다.
            </p>
          )}
        </div>

        {noCredit ? (
          <p className="banner-warn m-0">쓸 수 있는 크레딧이 없어 입찰할 수 없습니다.</p>
        ) : null}
        {!loading && available === null ? (
          <p className="banner-neutral m-0">
            보유 크레딧을 확인할 수 없습니다. 입찰액이 잔액보다 크면 접수 후 거절됩니다.
          </p>
        ) : null}

        <RuleNote>
          팀 입찰액(1인당 × 인원)이 가장 큰 팀이 확정되고, 같으면 이번 학기 노쇼가 적은 팀, 그다음 인원이 많은 팀이
          이깁니다. 확정되면 전원이 같은 금액을 내고, 탈락하면 묶인 크레딧을 그대로 돌려받습니다. 크레딧은 학기가 바뀔 때만 지급량으로
          다시 채워지고, 남은 크레딧은 이월되지 않습니다.
        </RuleNote>
      </div>
    );
  },
});
