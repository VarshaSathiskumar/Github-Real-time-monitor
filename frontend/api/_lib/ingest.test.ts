import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { type Deps, claim, ingestConfigFromEnv, maybeIngest } from './ingest.js'
import { settingsFromEnv } from './model.js'
import { testDb } from './test-db.js'

const T = Date.parse('2026-03-10T12:00:30Z') // event time (observed_at) used by the tests
const { momentum } = settingsFromEnv({})

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>

const gh = (id: number, type: string, repo: string, payload: object = {}): object => ({
  id: String(id), type, actor: { login: `user${id}` }, repo: { id: id % 7, name: repo }, payload,
  created_at: '2026-03-10T11:00:00Z', // an hour before it is observed
})
const page = (events: object[], headers: Record<string, string> = {}, status = 200): Response =>
  new Response(JSON.stringify(events), { status, headers })

describe('visitor-triggered ingestion', () => {
  let ctx: Awaited<ReturnType<typeof testDb>>
  let requests: { url: string; headers: Record<string, string> }[]
  let handler: Handler
  const logs: string[] = []

  const deps = (over: Partial<Deps> = {}): Deps => ({
    db: ctx.db,
    momentum,
    now: () => T,
    log: (m) => logs.push(m),
    config: { ...ingestConfigFromEnv({}), pages: 1 },
    fetch: (async (url: string, init: RequestInit) => {
      requests.push({ url, headers: init.headers as Record<string, string> })
      return handler(url, init)
    }) as typeof fetch,
    ...over,
  })
  const state = async (): Promise<Record<string, any>> => (await ctx.db('SELECT * FROM ingestion_state'))[0]
  const makeDue = (): Promise<unknown> => ctx.pg.exec(`UPDATE ingestion_state SET next_poll_at = now() - interval '1 second'`)
  const count = async (table: string): Promise<number> => Number((await ctx.db(`SELECT count(*) AS n FROM ${table}`))[0].n)
  const minuteTotal = async (): Promise<number> => Number((await ctx.db(`SELECT coalesce(sum(total), 0) AS n FROM minute_aggregates`))[0].n)
  const repoTotal = async (): Promise<number> => Number((await ctx.db(`SELECT coalesce(sum(total), 0) AS n FROM repo_minute_aggregates`))[0].n)

  beforeEach(async () => {
    ctx = await testDb()
    requests = []
    logs.length = 0
    handler = () => page([])
  })
  afterEach(() => ctx.pg.close())

  it('stores new events and counts them at the observation minute, not at created_at', async () => {
    handler = () =>
      page([gh(3, 'PullRequestEvent', 'a/b', { action: 'opened' }), gh(2, 'WatchEvent', 'a/b'), gh(1, 'PushEvent', 'c/d', { size: 2 })], {
        etag: 'W/"v1"', 'x-poll-interval': '60', 'x-ratelimit-remaining': '4990',
      })
    const out = await maybeIngest(deps())

    expect(out).toEqual({ result: 'ok', inserted: 3, fetched: 3, failed: 0 })
    expect(await count('github_events')).toBe(3)
    const minutes = await ctx.db(`SELECT (extract(epoch from minute) * 1000)::bigint AS t, total, push, pull_request, watch, commits, commit_reports FROM minute_aggregates`)
    expect(minutes).toHaveLength(1)
    expect(Number(minutes[0].t)).toBe(Math.floor(T / 60_000) * 60_000)
    expect(minutes[0]).toMatchObject({ total: 3, push: 1, pull_request: 1, watch: 1, commits: 2, commit_reports: 1 })
    expect(await repoTotal()).toBe(3)
    const s = await state()
    expect(s).toMatchObject({ status: 'HEALTHY', events_ingested: 3, consecutive_failures: 0, poll_interval_seconds: 60, rate_limit_remaining: 4990, claim_token: null, claim_expires_at: null, last_error: '' })
    expect(s.etags).toEqual({ '1': 'W/"v1"' })
    expect(s.next_poll_at.getTime()).toBeGreaterThan(Date.now() + 50_000)
    expect(new Date(s.first_window_start).getTime()).toBe(Math.floor(T / 300_000) * 300_000)
  })

  it('does not poll again before the next poll time', async () => {
    await maybeIngest(deps())
    expect(await maybeIngest(deps())).toEqual({ result: 'skipped' })
    expect(requests).toHaveLength(1)
  })

  it('never inflates aggregates when the same events are seen again', async () => {
    const batch = [gh(3, 'PushEvent', 'a/b', { size: 1 }), gh(2, 'WatchEvent', 'a/b'), gh(1, 'ForkEvent', 'c/d')]
    handler = () => page(batch)
    await maybeIngest(deps())
    await makeDue()
    handler = () => page([gh(4, 'WatchEvent', 'a/b'), ...batch, batch[0]]) // one new, three replays, one duplicate in the batch
    const out = await maybeIngest(deps({ now: () => T + 90_000 }))

    expect(out).toMatchObject({ result: 'ok', inserted: 1 })
    expect(await count('github_events')).toBe(4)
    expect(await minuteTotal()).toBe(4)
    expect(await repoTotal()).toBe(4)
    expect((await state()).events_ingested).toBe(4)
  })

  it('sends the saved ETag and treats 304 as a quiet success', async () => {
    handler = () => page([gh(1, 'WatchEvent', 'a/b')], { etag: 'W/"v1"' })
    await maybeIngest(deps())
    await makeDue()
    handler = () => new Response(null, { status: 304, headers: { 'x-poll-interval': '90' } })
    const out = await maybeIngest(deps())

    expect(requests[1].headers['If-None-Match']).toBe('W/"v1"')
    expect(out).toEqual({ result: 'ok', inserted: 0, fetched: 0, failed: 0 })
    const s = await state()
    expect(s.etags).toEqual({ '1': 'W/"v1"' })
    expect(s).toMatchObject({ status: 'HEALTHY', poll_interval_seconds: 90 })
    expect(await minuteTotal()).toBe(1)
  })

  it('polls more pages only with a token, and sends the token only in the Authorization header', async () => {
    const config = { ...ingestConfigFromEnv({ GITHUB_TOKEN: 'secret-token', GITHUB_PAGES: '3' }), perPage: 2 }
    expect(config.pages).toBe(3)
    let n = 0
    handler = () => page([gh(++n, 'WatchEvent', 'a/b'), gh(++n, 'WatchEvent', 'a/b')])
    await maybeIngest(deps({ config }))

    expect(requests.map((r) => r.url)).toEqual([
      'https://api.github.com/events?per_page=2&page=1',
      'https://api.github.com/events?per_page=2&page=2',
      'https://api.github.com/events?per_page=2&page=3',
    ])
    expect(requests[0].headers.Authorization).toBe('Bearer secret-token')
    expect(requests.every((r) => !r.url.includes('secret'))).toBe(true)
    expect(ingestConfigFromEnv({}).pages).toBe(1)
    expect(await count('github_events')).toBe(6)
  })

  describe('claims', () => {
    it('lets exactly one of several simultaneous requests take the claim', async () => {
      const claims = await Promise.all([1, 2, 3, 4, 5].map(() => claim(ctx.db, 40)))
      expect(claims.filter((c) => c !== null)).toHaveLength(1)
    })

    it('recovers when the holder dies: the claim expires and the next request takes over', async () => {
      const dead = await claim(ctx.db, 40) // a function instance that crashes before finishing
      expect(dead).not.toBeNull()
      expect(await maybeIngest(deps())).toEqual({ result: 'skipped' }) // still held, nobody else polls
      expect(requests).toHaveLength(0)

      await ctx.pg.exec(`UPDATE ingestion_state SET claim_expires_at = now() - interval '1 second'`)
      handler = () => page([gh(1, 'WatchEvent', 'a/b')])
      expect(await maybeIngest(deps())).toMatchObject({ result: 'ok', inserted: 1 })
      expect((await state()).claim_token).toBeNull()
    })

    it('a holder whose claim expired cannot overwrite the state of its successor', async () => {
      let successor: string | undefined
      handler = async () => {
        // While this poll is "slow", its claim expires and another request takes over.
        await ctx.pg.exec(`UPDATE ingestion_state SET claim_expires_at = now() - interval '1 second'`)
        successor = (await claim(ctx.db, 40))?.token
        return page([gh(1, 'WatchEvent', 'a/b')], { etag: 'W/"late"' })
      }
      await maybeIngest(deps())

      const s = await state()
      expect(successor).toBeDefined()
      expect(s.claim_token).toBe(successor) // not released by the stale holder
      expect(s.etags).toEqual({}) // late ETag not committed
      expect(s.events_ingested).toBe(0)
      expect(await count('github_events')).toBe(1) // its events are stored (idempotently), just not its bookkeeping
    })
  })

  describe('failures never escape and always back off', () => {
    it('records GitHub errors, releases the claim and schedules a retry', async () => {
      handler = () => page([], {}, 500)
      const out = await maybeIngest(deps())

      expect(out.result).toBe('failed')
      const s = await state()
      expect(s).toMatchObject({ status: 'DEGRADED', consecutive_failures: 1, claim_token: null, claim_expires_at: null })
      expect(s.last_error).toContain('HTTP 500')
      expect(s.next_poll_at.getTime()).toBeGreaterThan(Date.now() + 50_000)
    })

    it('turns FAILING after three consecutive failures and recovers on success', async () => {
      handler = () => {
        throw new Error('network down')
      }
      for (let i = 0; i < 3; i++) {
        expect((await maybeIngest(deps())).result).toBe('failed')
        await makeDue()
      }
      expect(await state()).toMatchObject({ status: 'FAILING', consecutive_failures: 3 })

      handler = () => page([gh(1, 'WatchEvent', 'a/b')])
      expect((await maybeIngest(deps())).result).toBe('ok')
      expect(await state()).toMatchObject({ status: 'HEALTHY', consecutive_failures: 0, last_error: '' })
    })

    it('treats a malformed response body as a failure', async () => {
      handler = () => new Response('<html>oops</html>', { status: 200 })
      expect((await maybeIngest(deps())).result).toBe('failed')
      expect((await state()).status).toBe('DEGRADED')
    })

    it('waits for the rate limit reset and reports RATE_LIMITED', async () => {
      const reset = Math.floor(Date.now() / 1000) + 600
      handler = () => page([], { 'x-ratelimit-remaining': '0', 'x-ratelimit-reset': String(reset) }, 403)
      const out = await maybeIngest(deps({ now: () => Date.now() }))

      expect(out.result).toBe('failed')
      const s = await state()
      expect(s).toMatchObject({ status: 'RATE_LIMITED', rate_limit_remaining: 0 })
      expect(Math.abs(s.next_poll_at.getTime() / 1000 - (reset + 1))).toBeLessThan(5)
    })

    it('a database failure while writing aborts without recording events as poison, and the claim expires', async () => {
      handler = () => page([gh(1, 'WatchEvent', 'a/b'), gh(2, 'WatchEvent', 'a/b')])
      let writes = 0
      const flaky: Deps['db'] = async (text, params) => {
        if (text.includes('INSERT INTO github_events')) {
          writes++
          throw new Error('connection reset')
        }
        if (text.trim() === 'SELECT 1') throw new Error('connection reset')
        return ctx.db(text, params)
      }
      const out = await maybeIngest(deps({ db: flaky }))

      expect(out.result).toBe('failed')
      expect(writes).toBe(1) // no futile split into single events while the database is down
      expect(await count('processing_failures')).toBe(0)
      expect(await count('github_events')).toBe(0)
      expect((await state()).etags).toEqual({}) // ETag not advanced, so the events are fetched again
    })
  })

  describe('bad events', () => {
    it('routes malformed events to the failure table and keeps processing the rest', async () => {
      handler = () =>
        page([
          gh(1, 'PushEvent', 'a/b', { size: 1 }),
          { id: 'x', type: 'PushEvent' }, // not numeric id, no actor
          { ...gh(2, 'WatchEvent', 'a/b'), repo: { name: 'noslash' } },
          gh(3, 'WatchEvent', 'a/b'),
        ])
      const out = await maybeIngest(deps())

      expect(out).toEqual({ result: 'ok', inserted: 2, fetched: 2, failed: 2 })
      const f = await ctx.db('SELECT topic, record_key, stage, payload FROM processing_failures ORDER BY id')
      expect(f.map((r) => [r.topic, r.stage])).toEqual([['github.events.dlq', 'parse'], ['github.events.dlq', 'parse']])
      expect(f.map((r) => JSON.parse(String(r.payload)).reason).sort()).toEqual(['Event id is not numeric: x', 'repo.name is not owner/name: noslash'])
      expect(await minuteTotal()).toBe(2)
    })

    it('isolates an event the database rejects instead of blocking the batch behind it', async () => {
      const poison = gh(2, 'WatchEvent', 'a/b')
      ;(poison as any).actor.login = 'bad\u0000actor' // PostgreSQL text cannot hold NUL
      handler = () => page([gh(1, 'WatchEvent', 'a/b'), poison, gh(3, 'PushEvent', 'a/b', { size: 1 }), gh(4, 'WatchEvent', 'c/d')])
      const out = await maybeIngest(deps())

      expect(out).toEqual({ result: 'ok', inserted: 3, fetched: 4, failed: 1 })
      expect((await ctx.db('SELECT stage, record_key FROM processing_failures')).map((r) => [r.stage, r.record_key])).toEqual([['persist', '2']])
      expect(await minuteTotal()).toBe(3)
      expect(await repoTotal()).toBe(3)
    })

    it('rejects values wider than their columns the way the persist stage would', async () => {
      const long = gh(1, 'WatchEvent', 'a/b')
      ;(long as any).actor.login = 'x'.repeat(101)
      handler = () => page([long, gh(2, 'WatchEvent', 'a/b')])
      const out = await maybeIngest(deps())
      expect(out).toMatchObject({ inserted: 1, failed: 1 })
    })
  })

  describe('momentum', () => {
    const W = 300_000
    const windowStart = Math.floor(T / W) * W
    const seedHistory = async (repo: string, perWindow: number): Promise<void> => {
      for (let i = 1; i <= 6; i++) {
        await ctx.db(
          `INSERT INTO repo_minute_aggregates (minute, repo_name, total, push) VALUES (to_timestamp($1::double precision / 1000), $2, $3, $3)`,
          [windowStart - i * W, repo, perWindow],
        )
      }
      await ctx.db(`UPDATE ingestion_state SET first_window_start = to_timestamp($1::double precision / 1000)`, [windowStart - 10 * W])
    }
    const burst = (repo: string, n: number, from = 100): object[] => Array.from({ length: n }, (_, i) => gh(from + i, 'PushEvent', repo, { size: 1 }))

    it('flags a spike against the repository baseline, like the 5 minute window processor', async () => {
      await seedHistory('hot/repo', 2)
      handler = () => page([...burst('hot/repo', 20), ...burst('quiet/repo', 3, 500)])
      await maybeIngest(deps())

      const rows = await ctx.db('SELECT repo_name, current_count, baseline, pct_increase, level FROM rising_repositories')
      expect(rows).toHaveLength(1)
      expect(rows[0]).toMatchObject({ repo_name: 'hot/repo', current_count: 20, baseline: 2, pct_increase: 900, level: 'SURGING' })
    })

    it('labels a spike with no baseline a BURST without a percentage', async () => {
      await seedHistory('other/repo', 5) // gives the processor history, but not for this repository
      handler = () => page(burst('new/repo', 12))
      await maybeIngest(deps())

      const rows = await ctx.db(`SELECT level, pct_increase FROM rising_repositories WHERE repo_name = 'new/repo'`)
      expect(rows).toEqual([{ level: 'BURST', pct_increase: null }])
    })

    it('keeps the greatest count when the same window is evaluated again', async () => {
      await seedHistory('hot/repo', 2)
      handler = () => page(burst('hot/repo', 20))
      await maybeIngest(deps())
      await makeDue()
      handler = () => page(burst('hot/repo', 5, 900))
      await maybeIngest(deps({ now: () => T + 30_000 }))

      const rows = await ctx.db(`SELECT current_count FROM rising_repositories WHERE repo_name = 'hot/repo'`)
      expect(rows).toEqual([{ current_count: 25 }])
    })

    it('stays quiet during the first windows after start, when there is no history to compare with', async () => {
      handler = () => page(burst('hot/repo', 30))
      await maybeIngest(deps())
      expect(await count('rising_repositories')).toBe(0)
    })
  })

  it('applies retention at most once an hour', async () => {
    await ctx.pg.exec(`
      INSERT INTO github_events (event_id, event_type, kind, actor_login, repo_id, repo_name, description, created_at, ingested_at, observed_at)
        VALUES (900, 'WatchEvent', 'WATCH', 'a', 1, 'a/b', 'd', now(), now(), now() - interval '4 hours'),
               (901, 'WatchEvent', 'WATCH', 'a', 1, 'a/b', 'd', now(), now(), now() - interval '1 hour');
      INSERT INTO repo_minute_aggregates (minute, repo_name, total) VALUES (now() - interval '26 hours', 'a/b', 1), (now() - interval '2 hours', 'a/b', 1);
      INSERT INTO minute_aggregates (minute, total) VALUES (now() - interval '26 hours', 1);
      UPDATE ingestion_state SET last_cleanup_at = now() - interval '2 hours';`)
    await maybeIngest(deps())
    expect(await count('github_events')).toBe(1)
    expect(await count('repo_minute_aggregates')).toBe(1)
    expect(await count('minute_aggregates')).toBe(1) // minute totals are kept for 30 days

    await ctx.pg.exec(`INSERT INTO github_events (event_id, event_type, kind, actor_login, repo_id, repo_name, description, created_at, ingested_at, observed_at)
        VALUES (902, 'WatchEvent', 'WATCH', 'a', 1, 'a/b', 'd', now(), now(), now() - interval '5 hours')`)
    await makeDue()
    await maybeIngest(deps())
    expect(await count('github_events')).toBe(2) // not due again yet
  })
})
