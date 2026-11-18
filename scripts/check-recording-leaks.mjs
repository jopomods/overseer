// Checks an exported recording against its source database for personal data that
// anonymization should have removed: player UUIDs, player names, MOTD, device positions.
//
//   node scripts/check-recording-leaks.mjs <overseer.db> <recording.jsonl>
//
// Exits non-zero if anything leaked. See docs/guides/recordings.md.
import fs from 'node:fs'
import { DatabaseSync } from 'node:sqlite'

const [dbPath, recordingPath] = process.argv.slice(2)
if (!dbPath || !recordingPath) {
  console.error('usage: node scripts/check-recording-leaks.mjs <overseer.db> <recording.jsonl>')
  process.exit(2)
}

const db = new DatabaseSync(dbPath, { readOnly: true })
const text = fs.readFileSync(recordingPath, 'utf8')
const lower = text.toLowerCase()
const all = (sql) => db.prepare(sql).all()
const leaks = new Set()

const uuid = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi
const values = all('SELECT rid AS v FROM resource UNION SELECT value AS v FROM event WHERE value IS NOT NULL')
let uuids = 0
for (const { v } of values) {
  for (const u of String(v).match(uuid) ?? []) {
    uuids++
    if (lower.includes(u.toLowerCase())) leaks.add(`uuid ${u.slice(0, 8)}…`)
  }
}

const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const names = all(
  "SELECT DISTINCT value AS v FROM event WHERE attribute = 'name' AND resource_id IN " +
    "(SELECT id FROM resource WHERE type IN ('player', 'member'))",
).map((r) => r.v)
for (const name of names) {
  if (name && new RegExp(`(?<![A-Za-z0-9_])${escape(name)}(?![A-Za-z0-9_])`).test(text)) leaks.add(`name ${name}`)
}

for (const { v } of all("SELECT DISTINCT value AS v FROM event WHERE attribute = 'motd'")) {
  if (v && text.includes(v)) leaks.add('motd')
}

const positions = all("SELECT rid FROM resource WHERE type = 'device'").map((r) => r.rid.split('@')[1])
for (const p of positions) if (text.includes(`@${p}"`)) leaks.add(`position ${p}`)

console.log(`checked ${uuids} UUID occurrences, ${names.length} player names, ${positions.length} positions`)
if (leaks.size) {
  console.error(`LEAKS: ${[...leaks].join(', ')}`)
  process.exit(1)
}
console.log('no leaks')
