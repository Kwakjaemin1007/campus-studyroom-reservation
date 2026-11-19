import type { PartyCheck, PartyDraftRow } from '@/domain/party';
import { emptyRow } from '@/domain/party';

interface Props {
  readonly rows: readonly PartyDraftRow[];
  readonly onChange: (rows: PartyDraftRow[]) => void;
  readonly check: PartyCheck;
  readonly selfId: string;
  readonly disabled: boolean;
}

/**
 * 함께 이용하는 사람(학번). 정책과 무관한 운영 규칙이라 정책 UI 모듈 밖, 신청서 공통 칸에 둔다.
 * 동행의 동의 절차는 없고 신청자가 책임진다. 넣은 사람 전원에게 결과 알림이 간다.
 */
export function CompanionsField({ rows, onChange, check, selfId, disabled }: Props) {
  function update(i: number, patch: Partial<PartyDraftRow>) {
    onChange(rows.map((r, k) => (k === i ? { ...r, ...patch } : r)));
  }

  return (
    <fieldset className="m-0 flex flex-col gap-2 border-0 p-0" disabled={disabled}>
      <legend className="field-label mb-1 p-0">함께 이용하는 사람</legend>
      <p className="field-hint m-0">
        본인({selfId}) 포함 2명 이상, 재학생만 넣을 수 있습니다. 넣은 사람 모두에게 결과 알림이 가고, 같은 시간에는
        다른 예약을 할 수 없습니다.
      </p>
      {rows.map((row, i) => {
        const err = check.rowErrors[i];
        return (
          <div key={i} className="flex flex-col gap-1">
            <div className="flex items-center gap-2">
              <input
                className="field-input num min-w-0 flex-1"
                inputMode="numeric"
                placeholder="학번"
                aria-label={`함께 이용하는 사람 ${i + 1} 학번`}
                aria-invalid={err ? true : undefined}
                value={row.studentId}
                onChange={(e) => update(i, { studentId: e.target.value })}
              />
              {rows.length > 1 ? (
                <button
                  type="button"
                  className="btn-secondary h-9 px-2 text-xs"
                  onClick={() => onChange(rows.filter((_, k) => k !== i))}
                >
                  빼기
                </button>
              ) : null}
            </div>
            {err ? <p className="field-error m-0">{err}</p> : null}
          </div>
        );
      })}
      <button
        type="button"
        className="btn-secondary h-9 self-start px-3 text-xs"
        onClick={() => onChange([...rows, emptyRow()])}
      >
        한 명 더
      </button>
      {check.error ? <p className="field-error m-0">{check.error}</p> : null}
    </fieldset>
  );
}
