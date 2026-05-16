import { fail, redirect } from '@sveltejs/kit';
import type { Actions, PageServerLoad } from './$types';
import { libertyJson } from '$lib/server/liberty';
import { randomUUID } from 'node:crypto';

interface Payment {
  id: number;
  policyNumber: string;
  amount: string;
  currency: string;
  status: string;
  externalRef?: string | null;
  failureReason?: string | null;
}

/** Pre-generate the Idempotency-Key on the server and ship it down as a
 *  hidden field. The teaching point: the same key on retry collapses to
 *  the same Payment row, so double-submits never double-charge. We show
 *  the key in the UI so it's visible during demos. */
export const load: PageServerLoad = async ({ params, locals }) => {
  const session = await locals.auth();
  if (!session?.user) {
    throw redirect(302, `/auth/signin/wso2is?callbackUrl=${encodeURIComponent(`/policies/${params.number}/pay`)}`);
  }
  return {
    policyNumber: params.number,
    idempotencyKey: randomUUID(),
    user: session.user,
  };
};

export const actions: Actions = {
  default: async ({ request, params, locals }) => {
    const session = await locals.auth();
    if (!session?.user) throw redirect(302, '/auth/signin/wso2is');

    const data = await request.formData();
    const amountStr = String(data.get('amount') ?? '').trim();
    const currency = String(data.get('currency') ?? 'USD').trim();
    const idempotencyKey = String(data.get('idempotencyKey') ?? '').trim();

    const amount = Number(amountStr);
    if (!Number.isFinite(amount) || amount <= 0) {
      return fail(400, { values: { amount: amountStr, currency, idempotencyKey }, error: 'Amount must be a positive number.' });
    }
    if (!idempotencyKey) {
      return fail(400, { values: { amount: amountStr, currency, idempotencyKey }, error: 'Idempotency-Key is required.' });
    }

    try {
      const payment = await libertyJson<Payment>('POST', '/api/payments', {
        headers: { 'Idempotency-Key': idempotencyKey },
        body: { policyNumber: params.number, amount, currency },
        userId:    session.user.id    ?? undefined,
        userEmail: session.user.email ?? undefined,
      });
      return { payment, idempotencyKey };
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      // 502 from Liberty = gateway returned FAILED (e.g. amount 9999 DLQ demo).
      // We surface this to the UI and DON'T fail() — the form still shows the
      // attempted key so the user sees that retrying with the same key won't
      // re-charge.
      if (msg.includes('502')) {
        return fail(502, {
          values: { amount: amountStr, currency, idempotencyKey },
          error: 'Payment gateway declined the charge. Retry with the same Idempotency-Key — you will NOT be double-charged.',
        });
      }
      return fail(500, { values: { amount: amountStr, currency, idempotencyKey }, error: msg });
    }
  },
};
