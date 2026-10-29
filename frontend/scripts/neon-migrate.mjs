// Prepares a Neon (or any PostgreSQL) database for the serverless-lite deployment:
//   DATABASE_URL=postgres://... npm run db:migrate
// Applies the same V1__init.sql that Flyway applies in Docker Compose, then the lite-only ingestion_state table.
import { readFileSync } from 'node:fs'
import pg from 'pg'

const url = process.env.DATABASE_URL
if (!url) {
  console.error('Set DATABASE_URL to the Neon connection string (the pooled one is fine).')
  process.exit(1)
}
const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8')

const client = new pg.Client({ connectionString: url })
await client.connect()
try {
  const exists = await client.query("SELECT to_regclass('public.github_events') AS t")
  if (exists.rows[0].t === null) {
    await client.query(read('../../services/analytics-service/src/main/resources/db/migration/V1__init.sql'))
    console.log('Applied V1__init.sql')
  } else {
    console.log('V1 tables already exist, skipping V1__init.sql')
  }
  await client.query(read('../api/_lib/lite-schema.sql'))
  console.log('Applied lite-schema.sql')
} finally {
  await client.end()
}
