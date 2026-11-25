// Checks the translations in src/locales/:
// - every language has the same keys with the same placeholders
// - every key used in the code ($t('…'), t('…'), tr('…'), keypath="…") exists
// - every key in the files is used (keys built at runtime, e.g. t(`contact.types.${type}`), count by prefix)
// Runs before `npm run build`.

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

const root = new URL('../src/', import.meta.url).pathname
const localeDir = join(root, 'locales')

function flatten(obj, prefix = '', out = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object') flatten(v, key, out)
    else out[key] = String(v)
  }
  return out
}

const locales = Object.fromEntries(
  readdirSync(localeDir)
    .filter((f) => f.endsWith('.json'))
    .map((f) => [f.replace('.json', ''), flatten(JSON.parse(readFileSync(join(localeDir, f), 'utf8')))]),
)
const [base, ...others] = Object.keys(locales)
const problems = []

const placeholders = (s) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join(',')

for (const lang of others) {
  for (const key of Object.keys(locales[base])) {
    const a = locales[base][key]
    const b = locales[lang][key]
    if (b === undefined) problems.push(`${lang}.json is missing ${key}`)
    else if (placeholders(a.split(' | ').at(-1)) !== placeholders(b.split(' | ').at(-1))) {
      problems.push(`${key}: placeholders differ: ${JSON.stringify(a)} / ${JSON.stringify(b)}`)
    }
  }
  for (const key of Object.keys(locales[lang])) {
    if (!(key in locales[base])) problems.push(`${base}.json is missing ${key}`)
  }
}

// Keys used in the code
function files(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) files(path, out)
    else if (/\.(vue|ts)$/.test(name)) out.push(path)
  }
  return out
}
const used = new Set()
const prefixes = new Set()
for (const path of files(root)) {
  const source = readFileSync(path, 'utf8')
  const keys = [
    ...[...source.matchAll(/(?:\$t|\btr?)\(\s*'([\w.]+)'/g)].map((m) => m[1]),
    ...[...source.matchAll(/keypath="([\w.]+)"/g)].map((m) => m[1]),
  ]
  for (const key of keys) {
    used.add(key)
    if (!(key in locales[base])) problems.push(`${path.replace(root, 'src/')}: unknown key ${key}`)
  }
  for (const m of source.matchAll(/(?:\$t|\btr?)\(\s*`([\w.]+)\.\$\{/g)) prefixes.add(m[1] + '.')
}
for (const prefix of prefixes) {
  if (!Object.keys(locales[base]).some((k) => k.startsWith(prefix))) problems.push(`no keys with prefix ${prefix}`)
}
for (const key of Object.keys(locales[base])) {
  if (!used.has(key) && ![...prefixes].some((p) => key.startsWith(p))) problems.push(`unused key ${key}`)
}

if (problems.length) {
  console.error([...new Set(problems)].join('\n'))
  process.exit(1)
}
console.log(`i18n: ${Object.keys(locales[base]).length} keys in ${Object.keys(locales).join(', ')} – ok`)
