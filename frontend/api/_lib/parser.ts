import { type Kind, kindOf } from './model.js'

/** Port of EventParser: turns one raw GitHub event into the fields the dashboard stores. */

export class MalformedEventError extends Error {}

export interface ParsedEvent {
  id: number
  type: string
  kind: Kind
  actor: string
  repoId: number
  repoName: string
  repoUrl: string
  commits: number | null
  action: string | null
  description: string
  url: string
  createdAt: number
  ingestedAt: number
}

const GITHUB = 'https://github.com/'
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/

type Obj = Record<string, unknown>

const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

/** Jackson's path(): never throws, missing parents give an absent value. */
const path = (v: unknown, key: string): unknown => (isObj(v) ? v[key] : undefined)

/** Jackson's asText() for scalar nodes; containers and absent values have no text. */
function text(v: unknown): string {
  return typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean' ? String(v) : ''
}

function requireText(parent: unknown, field: string): string {
  const t = text(path(parent, field))
  if (t.trim() === '') throw new MalformedEventError(`Missing field: ${field}`)
  return t
}

function textOr(v: unknown, fallback: string): string {
  return typeof v === 'string' && v.trim() !== '' ? v : fallback
}

function parseId(v: unknown): number {
  if (v === undefined || v === null) throw new MalformedEventError('Missing event id')
  const t = text(v).trim()
  if (!/^[+-]?\d+$/.test(t) || !Number.isSafeInteger(Number(t))) {
    throw new MalformedEventError(`Event id is not numeric: ${text(v)}`)
  }
  return Number(t)
}

function parseInstant(s: string, field: string): number {
  const ms = INSTANT.test(s) ? Date.parse(s) : NaN
  // Date.parse accepts some impossible dates by rolling them over; Instant.parse does not.
  if (Number.isNaN(ms) || new Date(ms).toISOString().slice(0, 10) !== s.slice(0, 10)) {
    throw new MalformedEventError(`Bad timestamp in ${field}: ${s}`)
  }
  return ms
}

/** String.hashCode() of the JVM. */
function javaHash(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0
  return h
}

/** JsonNode.asLong(default): numbers truncate, numeric text parses, anything else gives the default. */
function asLong(v: unknown, fallback: number): number {
  if (typeof v === 'number') return Math.trunc(v)
  if (typeof v === 'string' && /^\s*[+-]?\d+\s*$/.test(v)) return Number(v)
  return fallback
}

const isInt = (v: unknown): v is number => typeof v === 'number' && v >= -2147483648 && v <= 2147483647

/** GitHub's events API stopped including commit counts in push payloads; null means not reported. */
function commitCount(payload: unknown): number | null {
  const size = path(payload, 'size')
  if (isInt(size)) return Math.max(0, Math.trunc(size))
  const distinct = path(payload, 'distinct_size')
  if (isInt(distinct)) return Math.max(0, Math.trunc(distinct))
  const commits = path(payload, 'commits')
  return Array.isArray(commits) ? commits.length : null
}

function pastTense(action: string | null, fallback: string): string {
  if (action === null || action.trim() === '') return fallback
  switch (action) {
    case 'opened':
    case 'closed':
    case 'reopened':
    case 'edited':
    case 'assigned':
    case 'labeled':
      return action
    case 'synchronize':
      return 'updated'
    default:
      return action.replace(/_/g, ' ')
  }
}

function humanize(type: string): string {
  const base = type.endsWith('Event') ? type.slice(0, -5) : type
  return base.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase() + ' activity'
}

export function parseEvent(input: unknown): ParsedEvent {
  let root = input
  if (typeof root === 'string') {
    if (root.trim() === '') throw new MalformedEventError('Empty payload')
    try {
      root = JSON.parse(root)
    } catch (e) {
      throw new MalformedEventError(`Invalid JSON: ${(e as Error).message}`)
    }
  }
  if (!isObj(root)) throw new MalformedEventError('Payload is not a JSON object')

  const id = parseId(root.id)
  const type = requireText(root, 'type')
  const actor = requireText(root.actor, 'login')
  const repoName = requireText(root.repo, 'name')
  if (repoName.indexOf('/') < 1) throw new MalformedEventError(`repo.name is not owner/name: ${repoName}`)
  const repoId = asLong(path(root.repo, 'id'), -Math.abs(javaHash(repoName)))
  const createdAt = parseInstant(requireText(root, 'created_at'), 'created_at')
  const ingestedRaw = root.ingested_at
  const ingestedAt =
    ingestedRaw !== undefined && ingestedRaw !== null ? parseInstant(text(ingestedRaw), 'ingested_at') : createdAt

  const kind = kindOf(type)
  const payload = root.payload
  const actionRaw = path(payload, 'action')
  const action = actionRaw === undefined || actionRaw === null ? null : text(actionRaw)
  const repoUrl = GITHUB + repoName
  let commits: number | null = null
  let url = repoUrl
  let description: string

  switch (kind) {
    case 'PUSH': {
      commits = commitCount(payload)
      const refRaw = path(payload, 'ref')
      const branch = (refRaw === undefined || refRaw === null ? '' : text(refRaw)).replace(/^refs\/heads\//, '')
      url = branch.trim() === '' ? repoUrl : `${repoUrl}/commits/${branch}`
      description =
        commits !== null && commits > 0
          ? `${actor} pushed ${commits} ${commits === 1 ? 'commit' : 'commits'} to ${repoName}`
          : `${actor} pushed to ${repoName}`
      break
    }
    case 'PULL_REQUEST':
      url = textOr(path(path(payload, 'pull_request'), 'html_url'), `${repoUrl}/pulls`)
      description = `${actor} ${pastTense(action, 'opened')} a pull request in ${repoName}`
      break
    case 'FORK':
      url = textOr(path(path(payload, 'forkee'), 'html_url'), `${repoUrl}/forks`)
      description = `${actor} forked ${repoName}`
      break
    case 'WATCH':
      url = `${repoUrl}/stargazers`
      description = `${actor} starred ${repoName}`
      break
    case 'ISSUES':
      url = textOr(path(path(payload, 'issue'), 'html_url'), `${repoUrl}/issues`)
      description = `${actor} ${pastTense(action, 'opened')} an issue in ${repoName}`
      break
    case 'ISSUE_COMMENT':
      url = textOr(path(path(payload, 'comment'), 'html_url'), `${repoUrl}/issues`)
      description = `${actor} commented on an issue in ${repoName}`
      break
    default:
      description = `${actor} triggered ${humanize(type)} in ${repoName}`
  }

  return {
    id, type, kind, actor, repoId, repoName, repoUrl, commits, action, description, url, createdAt, ingestedAt,
  }
}

export const ownerOf = (repoName: string): string => {
  const slash = repoName.indexOf('/')
  return slash > 0 ? repoName.slice(0, slash) : repoName
}
