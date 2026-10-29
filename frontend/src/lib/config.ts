const env = import.meta.env

/** URL of the "View Source on GitHub" button, configurable at build time. */
export const GITHUB_REPO_URL: string =
  env.VITE_GITHUB_REPO_URL || 'https://github.com/VarshaSathiskumar/Github-Real-time-monitor'

export const API_BASE: string = (env.VITE_API_BASE_URL as string | undefined) ?? ''

/**
 * VITE_REFRESH_MODE=snapshot selects the serverless-lite deployment (Vercel functions + Neon, no Kafka or Redis):
 * every refresh is one /api/snapshot request, and the function ingests from GitHub on demand.
 */
export const LITE_MODE: boolean = env.VITE_REFRESH_MODE === 'snapshot'

/**
 * The deployed site (Vercel) sets VITE_REFRESH_MODE=polling and refreshes from the REST endpoints instead of
 * opening a WebSocket. The backend WebSocket endpoint is unchanged and is still used everywhere else.
 */
export const POLLING_MODE: boolean = env.VITE_REFRESH_MODE === 'polling' || LITE_MODE

/** The lite deployment never polls faster than every 5 seconds: each refresh costs a function invocation and keeps the database awake. */
const configuredInterval = Number(env.VITE_POLL_INTERVAL_MS) || (LITE_MODE ? 10_000 : 3_000)
export const POLL_INTERVAL_MS: number = LITE_MODE ? Math.max(5_000, configuredInterval) : configuredInterval

/** The lite deployment stops refreshing after this long without any interaction (tabs left open overnight). */
export const IDLE_PAUSE_MS = 10 * 60_000

export function wsUrl(): string {
  const explicit = env.VITE_WS_URL as string | undefined
  if (explicit) return explicit
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${window.location.host}/ws`
}
