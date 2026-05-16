// Expose the auth session to every page's $page.data.session.
// @auth/sveltekit attaches `locals.auth()` once hooks.server.ts wires
// the handler; we read it once at the layout level so child pages and
// the layout itself can both render based on auth state.
import type { LayoutServerLoad } from './$types';

export const load: LayoutServerLoad = async (event) => {
  const session = await event.locals.auth();
  return { session };
};
