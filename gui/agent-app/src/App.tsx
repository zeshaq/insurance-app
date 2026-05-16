import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';
import { fetchSession, type Session } from './api';

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  `px-3 py-1.5 rounded text-sm ${
    isActive ? 'bg-white/15 text-white' : 'text-white/80 hover:text-white'
  }`;

export default function App() {
  const navigate = useNavigate();
  const location = useLocation();

  const { data: session, isLoading } = useQuery<Session | null>({
    queryKey: ['session'],
    queryFn: fetchSession,
    retry: false,
  });

  // Client-side auth gate: any signed-in user gets in for now. Adding a
  // role check is a one-line change once the JWT carries an "AGENT" group
  // (left as a follow-up — see /agent route in the BFF for the cookie
  // shape).
  useEffect(() => {
    if (!isLoading && !session?.user) navigate('/login', { replace: true, state: { from: location.pathname } });
  }, [isLoading, session, navigate, location]);

  if (isLoading) return <div className="p-10 text-slate-500 text-sm">Loading session…</div>;
  if (!session?.user) return null;

  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-[var(--color-brand)] text-[var(--color-brand-fg)]">
        <div className="max-w-6xl mx-auto px-6 py-3 flex items-center gap-6 flex-wrap">
          <span className="font-semibold">insurance-app · agent dashboard</span>
          <nav className="text-sm flex gap-2">
            <NavLink to="/" end className={navLinkClass}>Dashboard</NavLink>
            <NavLink to="/policies" className={navLinkClass}>Policies</NavLink>
            <NavLink to="/claims" className={navLinkClass}>Claims</NavLink>
          </nav>
          <div className="ml-auto text-sm flex items-center gap-3">
            <span className="opacity-90">{session.user.email ?? session.user.name ?? session.user.id}</span>
            <form action="/auth/signout" method="POST" className="inline">
              <button className="underline opacity-90 hover:opacity-100">Sign out</button>
            </form>
          </div>
        </div>
      </header>
      <main className="flex-1 max-w-6xl mx-auto w-full px-6 py-8">
        <Outlet />
      </main>
      <footer className="text-center text-xs text-slate-500 py-6">
        insurance-app · teaching artifact · slice 24 (agent dashboard)
      </footer>
    </div>
  );
}
