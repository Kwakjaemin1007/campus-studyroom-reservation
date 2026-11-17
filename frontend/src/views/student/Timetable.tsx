import { openHours, slotsOf, type Room } from '@/catalog/campusCatalog';
import { slotKey, type SlotRef, type TrackedStatus } from '@/domain/reservation';

export type BoardState =
  /** 첫 조회를 기다리는 중 */
  | { readonly kind: 'loading' }
  /** 서버가 점유 현황을 알려 줌 */
  | { readonly kind: 'ready'; readonly taken: ReadonlySet<string> }
  /** 서버에 점유 조회가 없음. 칸은 모두 신청할 수 있고, 마감 여부는 신청 후에 안다. */
  | { readonly kind: 'unknown' };

interface Props {
  readonly rooms: readonly Room[];
  readonly dateKey: string;
  readonly board: BoardState;
  /** 내 예약이 걸린 슬롯 → 진행 단계 */
  readonly mine: ReadonlyMap<string, TrackedStatus>;
  readonly selectedKey: string | null;
  readonly now: number;
  readonly onSelect: (slot: SlotRef) => void;
}

const ROOM_COL = 'w-[132px] min-w-[132px] sm:w-[150px] sm:min-w-[150px]';
const HEAD_CELL = 'h-8 border-b border-r border-line bg-canvas px-1 text-center text-2xs font-medium text-sub last:border-r-0';
const BODY_CELL = 'h-cell border-b border-r border-line p-0 last:border-r-0';

type CellKind = 'past' | 'mine' | 'pending' | 'taken' | 'selected' | 'open';

function kindOf(key: string, startsAt: Date, props: Props): CellKind {
  const status = props.mine.get(key);
  if (status === 'CONFIRMED' || status === 'CHECKED_IN') return 'mine';
  if (status === 'QUEUED' || status === 'REQUESTED') return 'pending';
  if (startsAt.getTime() <= props.now) return 'past';
  if (props.board.kind === 'ready' && props.board.taken.has(key)) return 'taken';
  if (props.selectedKey === key) return 'selected';
  return 'open';
}

const CELL_STYLE: Readonly<Record<CellKind, { className: string; text: string; label: string; clickable: boolean }>> = {
  past: { className: 'bg-canvas text-mute', text: '', label: '지난 시간', clickable: false },
  taken: { className: 'bg-canvas text-sub', text: '마감', label: '마감', clickable: false },
  mine: { className: 'bg-ink font-semibold text-white', text: '내 예약', label: '내 예약', clickable: true },
  pending: { className: 'bg-white font-medium text-ink ring-1 ring-inset ring-ink', text: '신청 중', label: '신청 중', clickable: true },
  // 선택된 타임슬롯: KU 그린을 쓰는 세 곳 중 하나
  selected: { className: 'bg-ku-tint font-semibold text-ku ring-1 ring-inset ring-ku', text: '선택', label: '선택됨', clickable: true },
  open: { className: 'bg-white text-sub hover:bg-canvas', text: '', label: '신청 가능', clickable: true },
};

/** 호실 × 시간 타임테이블. 가로로 넘치면 표 안에서만 스크롤하고 호실 열은 고정한다. */
export function Timetable(props: Props) {
  const hours = openHours();

  return (
    <div className="surface">
      <div className="overflow-x-auto">
        <table className="w-full border-separate border-spacing-0 text-sm" aria-busy={props.board.kind === 'loading'}>
          <caption className="sr-only">호실별 시간표. 빈 칸을 누르면 예약을 신청할 수 있습니다.</caption>
          <thead>
            <tr>
              <th scope="col" className={`${HEAD_CELL} ${ROOM_COL} sticky left-0 z-10 px-3 text-left`}>
                호실 · 정원
              </th>
              {hours.map((h) => (
                <th key={h} scope="col" className={`${HEAD_CELL} num min-w-[44px]`}>
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {props.rooms.map((room, rowIndex) => {
              const lastRow = rowIndex === props.rooms.length - 1;
              return (
                <tr key={room.roomId}>
                  <th
                    scope="row"
                    className={`${BODY_CELL} ${ROOM_COL} sticky left-0 z-10 bg-white px-3 text-left font-normal ${lastRow ? 'border-b-0' : ''}`}
                  >
                    <span className="flex items-center justify-between gap-2">
                      <span className="truncate text-ink">{room.name}</span>
                      <span className="num shrink-0 text-xs text-sub">{room.capacity}인</span>
                    </span>
                  </th>
                  {slotsOf(room, props.dateKey).map((slot, i) => {
                    const cellClass = `${BODY_CELL} min-w-[44px] ${lastRow ? 'border-b-0' : ''}`;
                    if (props.board.kind === 'loading') {
                      return (
                        <td key={i} className={cellClass}>
                          <span className="skeleton mx-auto block h-4 w-6" />
                        </td>
                      );
                    }
                    const key = slotKey(slot);
                    const kind = kindOf(key, slot.startsAt, props);
                    const style = CELL_STYLE[kind];
                    const hour = hours[i] ?? 0;
                    return (
                      <td key={i} className={cellClass}>
                        <button
                          type="button"
                          className={`block h-full w-full rounded-none border-0 text-2xs ${style.className} ${
                            style.clickable ? 'cursor-pointer' : 'cursor-default'
                          }`}
                          disabled={!style.clickable}
                          aria-pressed={kind === 'selected' ? true : undefined}
                          aria-label={`${room.name} ${hour}시, ${style.label}`}
                          onClick={() => props.onSelect(slot)}
                        >
                          {style.text}
                        </button>
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Swatch({ className, label }: { className: string; label: string }) {
  return (
    <span className="flex items-center gap-1.5">
      <span className={`inline-block h-3.5 w-3.5 rounded-none ${className}`} aria-hidden="true" />
      {label}
    </span>
  );
}

export function TimetableLegend({ showTaken }: { showTaken: boolean }) {
  return (
    <div className="flex flex-wrap gap-x-5 gap-y-1 text-xs text-sub">
      <Swatch className="border border-line-strong bg-white" label="신청 가능" />
      {showTaken ? <Swatch className="border border-line bg-canvas" label="마감 · 지난 시간" /> : null}
      <Swatch className="bg-ku-tint ring-1 ring-inset ring-ku" label="선택" />
      <Swatch className="bg-white ring-1 ring-inset ring-ink" label="신청 중" />
      <Swatch className="bg-ink" label="내 예약" />
    </div>
  );
}
