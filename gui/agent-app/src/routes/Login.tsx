// Static landing for signed-out users. The "Sign in" button POSTs to the
// BFF's /auth/signin which 302s to the WSO2 IS authorize endpoint.
export default function Login() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <div className="bg-white border border-slate-200 rounded-lg p-10 shadow-sm max-w-md w-full">
        <p className="text-xs uppercase tracking-wide text-[var(--color-brand)]">insurance-app</p>
        <h1 className="mt-1 text-2xl font-semibold text-slate-900">Agent dashboard</h1>
        <p className="mt-3 text-sm text-slate-600">
          Sign in with your insurance-app identity to review policies, file
          activity, and approve claims.
        </p>
        <form action="/auth/signin" method="POST" className="mt-6">
          <button className="bg-[var(--color-brand)] text-white px-5 py-2.5 rounded font-medium hover:opacity-90 w-full">
            Sign in with WSO2 IS
          </button>
        </form>
      </div>
    </div>
  );
}
