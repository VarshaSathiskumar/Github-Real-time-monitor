import { neon } from '@neondatabase/serverless'
import type { Db } from './postgres.js'

/** Neon over HTTP: one request per statement, no connection to keep open in a function. */
export function neonDb(url: string | undefined = process.env.DATABASE_URL): Db {
  if (!url) throw new Error('DATABASE_URL is not set')
  const sql = neon(url)
  return async (text, params = []) => (await sql.query(text, params)) as Record<string, unknown>[]
}
