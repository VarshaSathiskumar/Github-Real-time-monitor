const env = import.meta.env

/** URL of the "View Source on GitHub" button, configurable at build time. */
export const GITHUB_REPO_URL: string =
  env.VITE_GITHUB_REPO_URL || 'https://github.com/VarshaSathiskumar/Github-Real-time-monitor'

export const API_BASE: string = (env.VITE_API_BASE_URL as string | undefined) ?? ''

/**
 * The deployed site (Vercel) sets VITE_REFRESH_MODE=polling and refreshes from the REST endpoints instead of
 * opening a WebSocket. The backend WebSocket endpoint is unchanged and is still used everywhere else.
 */
export const POLLING_MODE: boolean = env.VITE_REFRESH_MODE === 'polling'

export const POLL_INTERVAL_MS = 3_000

export function wsUrl(): string {
  const explicit = env.VITE_WS_URL as string | undefined
  if (explicit) return explicit
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${window.location.host}/ws`
}
