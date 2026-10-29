import { readFileSync } from 'node:fs'
import { PGlite } from '@electric-sql/pglite'
import type { Db } from './postgres.js'

const read = (rel: string): string => readFileSync(new URL(rel, import.meta.url), 'utf8')

/** An in-process PostgreSQL (WASM) with the real V1 migration plus the lite schema, no Docker needed. */
export async function testDb(): Promise<{ db: Db; pg: PGlite }> {
  const pg = new PGlite()
  await pg.exec(read('../../../services/analytics-service/src/main/resources/db/migration/V1__init.sql'))
  await pg.exec(read('./lite-schema.sql'))
  const db: Db = async (text, params = []) => (await pg.query(text, params)).rows as Record<string, unknown>[]
  return { db, pg }
}

export const fixture = (name: string): unknown => JSON.parse(read(`../../../parity/${name}`))
