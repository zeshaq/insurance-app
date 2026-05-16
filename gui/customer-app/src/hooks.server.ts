import { handle as authHandle } from './auth';

// Single handle for now — @auth/sveltekit owns request handling for
// /auth/* (signin / callback / signout / session). Subsequent slices
// can sequence(authHandle, …) to add per-request logging, CSP, etc.
export const handle = authHandle;
