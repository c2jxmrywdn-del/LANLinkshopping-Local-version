import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const frontendRoot = path.resolve(scriptDir, '..')
const root = path.resolve(frontendRoot, 'src')
const expected = new Map([
  ['营业执照', 'Business License'],
  ['税务登记号', 'Tax Registration No.'],
  ['查看原件', 'View Original'],
  ['记录', 'Record'],
])

const sourceFiles = []
function walk(dir) {
  if (!fs.existsSync(dir)) return
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(full)
    else if (/\.(js|jsx|ts|tsx|vue|json)$/.test(entry.name)) sourceFiles.push(full)
  }
}
walk(root)

function countKeyDefinitions(content, key) {
  let count = 0
  for (const token of ["'" + key + "'", '"' + key + '"']) {
    let cursor = 0
    while (true) {
      const index = content.indexOf(token, cursor)
      if (index < 0) break
      let next = index + token.length
      while (/\\s/.test(content[next] || '')) next += 1
      if (content[next] === ':') count += 1
      cursor = next + 1
    }
  }
  return count
}

const targetDefinitions = new Map()
for (const file of sourceFiles) {
  const content = fs.readFileSync(file, 'utf8')
  for (const [zh] of expected) {
    const count = countKeyDefinitions(content, zh)
    if (count) {
      const key = path.relative(process.cwd(), file).replaceAll(path.sep, '/')
      targetDefinitions.set(zh, [...(targetDefinitions.get(zh) || []), ...Array(count).fill(key)])
    }
  }
}

const failures = []
for (const [zh] of expected) {
  const locations = targetDefinitions.get(zh) || []
  if (locations.length !== 1) {
    failures.push(zh + ': expected exactly 1 translation-style definition, found ' + locations.length + ' (' + (locations.join(', ') || 'none') + ')')
  }
}

const i18nFile = path.resolve(frontendRoot, 'src/i18n/index.js')
const source = fs.readFileSync(i18nFile, 'utf8')
const start = source.indexOf('const UI_ZH_EN = {')
const closeMatch = start < 0 ? -1 : source.slice(start).search(/\r?\n\}\r?\n\r?\nconst _zhOrder/)
const end = closeMatch < 0 ? -1 : start + closeMatch

if (start < 0 || end < 0) {
  failures.push('UI_ZH_EN dictionary block could not be located')
} else {
  const block = source.slice(start, end)
  const keyPattern = /['"]([^'"]+)['"]\s*:/g
  const keys = [...block.matchAll(keyPattern)].map(match => match[1])
  const counts = new Map()
  for (const key of keys) counts.set(key, (counts.get(key) || 0) + 1)

  for (const [key, count] of counts) {
    if (count > 1) failures.push('UI_ZH_EN duplicate key: ' + key + ' (' + count + ' definitions)')
  }

  for (const [zh, en] of expected) {
    const exact = "'" + zh + "':'" + en + "'"
    const spaced = "'" + zh + "': '" + en + "'"
    if (!block.includes(exact) && !block.includes(spaced)) {
      failures.push(zh + ': expected translation ' + en)
    }
  }
}

// zh-en-extra.js 冲突检测：同键不同值 = 失败（后键静默覆盖前键，属真缺陷）；
// 同键同值冗余仅 warning 不失败——该文件存在大量历史同值冗余，避免既有项炸 CI。
const extraFile = path.resolve(frontendRoot, 'src/i18n/zh-en-extra.js')
const extraSource = fs.readFileSync(extraFile, 'utf8')
const extraStart = extraSource.indexOf('export const UI_ZH_EN_EXTRA = {')
// 块结尾 = 顶层收尾花括号行（兼容 LF/CRLF 行尾）
const extraClose = extraStart < 0 ? -1 : extraSource.slice(extraStart).search(/\n\}(\r?\n|$)/)
const extraEnd = extraStart < 0 || extraClose < 0 ? -1 : extraStart + extraClose

if (extraStart < 0 || extraEnd < 0) {
  failures.push('UI_ZH_EN_EXTRA dictionary block could not be located')
} else {
  const extraBlock = extraSource.slice(extraStart, extraEnd)
  // 单引号/双引号键值对（值可含转义引号），一行可写多对
  const pairPattern = /(['"])((?:\\.|(?!\1).)*)\1\s*:\s*(['"])((?:\\.|(?!\3).)*)\3/g
  const defs = new Map()
  for (const match of extraBlock.matchAll(pairPattern)) {
    const line = extraSource.slice(0, extraStart + match.index).split('\n').length
    const key = unescapeExtra(match[2])
    const value = unescapeExtra(match[4])
    if (!defs.has(key)) defs.set(key, [])
    defs.get(key).push({ line, value })
  }

  const warnings = []
  for (const [key, entries] of defs) {
    if (entries.length < 2) continue
    const values = new Set(entries.map(e => e.value))
    if (values.size > 1) {
      const detail = entries.map(e => 'L' + e.line + ' ' + JSON.stringify(e.value)).join(' | ')
      failures.push('zh-en-extra conflicting duplicate key: ' + key + ' (' + detail + ')')
    } else {
      warnings.push(key + ' x' + entries.length)
    }
  }
  if (warnings.length) {
    console.warn('i18n zh-en-extra redundant same-value duplicates (warning only, ' + warnings.length + '):')
    for (const w of warnings) console.warn('- ' + w)
  }
}

function unescapeExtra(raw) {
  return raw.replace(/\\(['"`\\])/g, '$1')
}

if (failures.length) {
  console.error('i18n duplicate/consistency check failed:')
  for (const failure of failures) console.error('- ' + failure)
  process.exit(1)
}

console.log('i18n duplicate/consistency check passed:')
for (const [zh, en] of expected) console.log('- ' + zh + ' -> ' + en)
