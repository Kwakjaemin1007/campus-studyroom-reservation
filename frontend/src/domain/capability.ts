/**
 * 서버에 따라 없을 수 있는 조회 기능을 다루는 결과 타입(조회 API 가 없는 예전 서버 등).
 * supported=false 면 화면은 값을 지어내지 않고 "알 수 없음" 상태를 보여 준다.
 */
export type Capability<T> =
  | { readonly supported: true; readonly value: T }
  | { readonly supported: false; readonly reason: string };

export function supported<T>(value: T): Capability<T> {
  return { supported: true, value };
}

export function unsupported<T>(reason: string): Capability<T> {
  return { supported: false, reason };
}
