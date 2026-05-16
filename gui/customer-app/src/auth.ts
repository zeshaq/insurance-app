import { SvelteKitAuth } from '@auth/sveltekit';
import { env } from '$env/dynamic/private';

/**
 * @auth/sveltekit configured against WSO2 IS.
 *
 * WSO2 IS isn't in @auth/sveltekit's built-in providers list, so we
 * declare it as a custom OIDC provider with explicit endpoint URLs.
 * The IS hostname matches what's baked into IS's deployment.toml and
 * Liberty's mpJwt issuer, so the JWT iss claim lines up everywhere.
 *
 * Dynamic-private env vars — read at runtime from process.env, NOT
 * baked into the bundle at build time. The Containerfile doesn't have
 * a .env file; the values come from `podman run -e ...` at start.
 *
 * The cookie is signed with AUTH_SECRET. The SvelteKit server holds
 * the session; the browser never sees a JWT.
 */
export const { handle, signIn, signOut } = SvelteKitAuth(async () => {
  return {
    secret: env.AUTH_SECRET,
    trustHost: true,
    providers: [
      {
        id: 'wso2is',
        name: 'WSO2 Identity Server',
        type: 'oidc',
        // WSO2 IS 7's `iss` claim — and its discovery URL — both live
        // under /oauth2/token, not at the bare host. oauth4webapi (under
        // @auth/sveltekit) computes the discovery URL as
        // `<issuer>/.well-known/openid-configuration` and validates that
        // discovery.issuer === config.issuer; both work out only when
        // issuer carries the /oauth2/token suffix.
        issuer: 'https://is.insurance-app.comptech-lab.com/oauth2/token',
        wellKnown:
          'https://is.insurance-app.comptech-lab.com/oauth2/token/.well-known/openid-configuration',
        clientId: env.CUSTOMER_OIDC_CLIENT_ID,
        clientSecret: env.CUSTOMER_OIDC_CLIENT_SECRET,
        authorization: { params: { scope: 'openid profile email' } },
        checks: ['pkce', 'state'],
        profile(p) {
          return {
            id: p.sub as string,
            name: (p.name as string | undefined) ?? (p.given_name as string | undefined) ?? (p.sub as string),
            email: (p.email as string | undefined) ?? null,
          };
        },
      },
    ],
    pages: { signIn: '/login' },
    callbacks: {
      // Stash the upstream access_token in the session JWT so server-side
      // load() functions can forward it to Liberty if we ever want
      // user-identity propagation. For slice 18 the BFF uses a service-
      // account token; this is plumbing for later slices.
      async jwt({ token, account }) {
        if (account?.access_token) token.accessToken = account.access_token;
        return token;
      },
      async session({ session, token }) {
        return { ...session, _hasIdpToken: !!token.accessToken };
      },
    },
  };
});
