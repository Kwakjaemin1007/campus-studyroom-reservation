import { AppShell, type NavItem } from '@/app/AppShell';
import { SignInPanel } from '@/app/SignInPanel';
import { useHashRoute } from '@/app/useHashRoute';
import { useSession } from '@/app/useSession';
import { FoundationCheck } from '@/dev/FoundationCheck';
import { AdminPolicyConsole } from '@/views/admin/AdminPolicyConsole';
import { QrKioskView } from '@/views/kiosk/QrKioskView';
import { StudentBookingView } from '@/views/student/StudentBookingView';

const NAV: readonly NavItem[] = [
  { path: '/', label: '예약' },
  { path: '/admin', label: '관리자' },
  { path: '/kiosk', label: '키오스크' },
  { path: '/dev', label: '연결 점검' },
];

/**
 * 화면 넷: 학생 예약(/), 관리자 정책 콘솔(/admin), QR 키오스크(/kiosk), 연결 점검(/dev).
 * 키오스크는 호실 입구의 태블릿에 띄우는 화면이라 머리줄 없이 전체 화면으로 그린다.
 */
export function App() {
  const route = useHashRoute();
  const session = useSession();

  if (route.path === '/kiosk') return <QrKioskView initialCode={route.params.get('code')} />;

  const activePath = NAV.some((n) => n.path === route.path) ? route.path : '/';
  let body;
  if (activePath === '/dev') {
    body = <FoundationCheck />;
  } else if (activePath === '/admin') {
    body =
      session?.role === 'admin' ? (
        <AdminPolicyConsole key={session.userId} session={session} />
      ) : (
        <SignInPanel role="admin" current={session} />
      );
  } else {
    body =
      session?.role === 'student' ? (
        <StudentBookingView key={session.userId} session={session} />
      ) : (
        <SignInPanel role="student" current={session} />
      );
  }

  return (
    <AppShell nav={NAV} activePath={activePath} session={session}>
      {body}
    </AppShell>
  );
}
