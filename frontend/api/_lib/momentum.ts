import type { RisingRepo } from '../../src/lib/types.js'
import { type MomentumConfig, MINUTE, iso } from './model.js'

/** Port of MomentumRules, the pure spike detection rule shared with the Kafka Streams processor. */

export interface Spike {
  repo: string
  repoUrl: string
  windowStart: number
  windowMinutes: number
  currentCount: number
  baseline: number
  pctIncrease: number | null
  level: 'BURST' | 'WARMING' | 'RISING' | 'SURGING'
  note: string
  detectedAt: number
}

const round1 = (v: number): number => Math.round(v * 10) / 10

export const BURST_NOTE = 'Almost no earlier activity in the observed sample, so no percentage is shown.'
export const SPIKE_NOTE = 'Activity spike in the observed event sample.'

/**
 * @param history window start (epoch millis) to event count, for the windows before and including the current one
 * @param firstWindowStart start of the very first window ever observed (epoch millis)
 */
export function evaluate(
  cfg: MomentumConfig,
  repo: string,
  windowStart: number,
  currentCount: number,
  history: Map<number, number>,
  firstWindowStart: number,
  detectedAt: number,
): Spike | null {
  const sizeMs = cfg.windowMinutes * MINUTE
  const completeWindowsObserved = Math.max(0, Math.trunc((windowStart - firstWindowStart) / sizeMs))
  if (completeWindowsObserved < cfg.minHistoryWindows || currentCount < cfg.minEvents) return null

  const n = Math.min(cfg.baselineWindows, completeWindowsObserved)
  let sum = 0
  for (let i = 1; i <= n; i++) sum += history.get(windowStart - i * sizeMs) ?? 0
  const baseline = sum / n

  const baselineTooSmall = baseline < cfg.minBaseline
  if (!baselineTooSmall && currentCount < cfg.spikeRatio * baseline) return null

  const pct = baselineTooSmall ? null : round1(((currentCount - baseline) / baseline) * 100)
  let level: Spike['level']
  if (pct === null) level = 'BURST'
  else if (pct >= 500) level = 'SURGING'
  else if (pct >= 200) level = 'RISING'
  else level = 'WARMING'

  return {
    repo,
    repoUrl: `https://github.com/${repo}`,
    windowStart,
    windowMinutes: cfg.windowMinutes,
    currentCount,
    baseline: round1(baseline),
    pctIncrease: pct,
    level,
    note: level === 'BURST' ? BURST_NOTE : SPIKE_NOTE,
    detectedAt,
  }
}

export const spikeToWire = (s: Spike): RisingRepo => ({
  repo: s.repo,
  repoUrl: s.repoUrl,
  windowStart: iso(s.windowStart),
  windowMinutes: s.windowMinutes,
  currentCount: s.currentCount,
  baseline: s.baseline,
  pctIncrease: s.pctIncrease,
  level: s.level,
  note: s.note,
  detectedAt: iso(s.detectedAt),
})
