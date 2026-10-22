const env = import.meta.env

/** URL of the "View Source on GitHub" button, configurable at build time. */
export const GITHUB_REPO_URL: string =
  env.VITE_GITHUB_REPO_URL || 'https://github.com/VarshaSathiskumar/Github-Real-time-monitor'

export const API_BASE: string = (env.VITE_API_BASE_URL as string | undefined) ?? ''

export function wsUrl(): string {
  const explicit = env.VITE_WS_URL as string | undefined
  if (explicit) return explicit
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${window.location.host}/ws`
}
