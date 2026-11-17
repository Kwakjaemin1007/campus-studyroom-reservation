import { useEffect, useState } from 'react';

/**
 * 해시 기반 라우팅(#kiosk 처럼). 예전 형식 #/kiosk 도 받는다.
 * 슬래시 없는 이름을 쓰는 까닭: claude.ai 에 올린 페이지는 주소의 해시 중 #이름 형태만 전달한다. 화면이 넷뿐이라 라우터 라이브러리를 쓰지 않는다.
 * 해시는 서버로 가지 않으므로 정적 파일 서버 설정 없이도 새로고침이 된다.
 */
export interface HashRoute {
  /** 예: '/', '/kiosk' */
  readonly path: string;
  readonly params: URLSearchParams;
}

function parse(hash: string): HashRoute {
  const raw = hash.replace(/^#/, '') || '/';
  const q = raw.indexOf('?');
  const path = `/${(q < 0 ? raw : raw.slice(0, q)).replace(/^\/+/, '')}`;
  return { path, params: new URLSearchParams(q < 0 ? '' : raw.slice(q + 1)) };
}

export function useHashRoute(): HashRoute {
  const [route, setRoute] = useState(() => parse(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parse(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}

export function hrefOf(path: string, params?: Record<string, string>): string {
  const query = params ? new URLSearchParams(params).toString() : '';
  const name = path.replace(/^\/+/, '');
  return `#${name}${query ? `?${query}` : ''}`;
}
