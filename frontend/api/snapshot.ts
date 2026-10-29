import { waitUntil } from '@vercel/functions'
import { handleSnapshot } from './_lib/snapshot.js'

export function GET(request: Request): Promise<Response> {
  return handleSnapshot(request, { waitUntil })
}
