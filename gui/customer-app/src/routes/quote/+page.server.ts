import { fail, type Actions } from '@sveltejs/kit';
import { libertyJson } from '$lib/server/liberty';

interface Quote {
  id: number;
  vehicleVin: string;
  driverAge: number;
  coverageType: string;
  premium: string;
  status: string;
  validUntil: string;
}

interface FormValues {
  vehicleVin?: string;
  driverAge?: string;
  coverageType?: string;
}

/** Get-a-quote is intentionally anonymous — matches the typical insurer
 *  flow where pricing is publicly browseable, login is required only at
 *  bind / payment time. The BFF still hits Liberty with a real JWT
 *  (the service-account `client_credentials` token), but no user identity
 *  is propagated for this endpoint. */
export const actions: Actions = {
  default: async ({ request, locals }) => {
    const data = await request.formData();
    const values: FormValues = {
      vehicleVin:   String(data.get('vehicleVin')   ?? '').trim(),
      driverAge:    String(data.get('driverAge')    ?? '').trim(),
      coverageType: String(data.get('coverageType') ?? '').trim(),
    };

    if (!values.vehicleVin || !values.driverAge || !values.coverageType) {
      return fail(400, { values, error: 'All three fields are required.' });
    }
    const age = parseInt(values.driverAge, 10);
    if (Number.isNaN(age) || age < 16 || age > 99) {
      return fail(400, { values, error: 'Driver age must be between 16 and 99.' });
    }
    if (!['BASIC', 'STANDARD', 'PREMIUM'].includes(values.coverageType)) {
      return fail(400, { values, error: 'Coverage must be BASIC, STANDARD, or PREMIUM.' });
    }

    // Propagate user id if there's an authenticated session, but don't
    // require one — anonymous quotes are fine.
    const session = await locals.auth();
    const opts =
      session?.user
        ? { userId: session.user.id ?? undefined, userEmail: session.user.email ?? undefined }
        : {};

    try {
      const quote = await libertyJson<Quote>('POST', '/api/quotes', {
        body: {
          vehicleVin:   values.vehicleVin,
          driverAge:    age,
          coverageType: values.coverageType,
        },
        ...opts,
      });
      return { values, quote };
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      // Liberty 429 surfaces as a thrown error from libertyJson; show a
      // useful message instead of the raw stack.
      if (msg.includes('429')) {
        return fail(429, {
          values,
          error: 'Too many quotes for this VIN in the last minute. Try a different VIN or wait a bit.',
        });
      }
      return fail(502, { values, error: msg });
    }
  },
};
