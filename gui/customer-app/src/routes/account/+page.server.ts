// Gate /account behind a real session. Without it the page renders the
// signed-out fallback, but a server-side redirect to /auth/signin is a
// cleaner UX once a user has clicked through marketing pages already.
import { redirect } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async (event) => {
  const session = await event.locals.auth();
  if (!session?.user) throw redirect(302, '/auth/signin/wso2is?callbackUrl=/account');
  return { session };
};
