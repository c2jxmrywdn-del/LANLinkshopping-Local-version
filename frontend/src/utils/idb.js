// 极简 IndexedDB 封装（Promise 化）：db 名 ll_cache，store kv
// 用于页面数据缓存（profile / settings）与 AES 密钥托管

const DB_NAME = 'll_cache'
const STORE = 'kv'

function openDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1)
    req.onupgradeneeded = () => {
      if (!req.result.objectStoreNames.contains(STORE)) {
        req.result.createObjectStore(STORE)
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

function tx(mode, fn) {
  return openDB().then((db) => new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode)
    const store = t.objectStore(STORE)
    const out = fn(store)
    t.oncomplete = () => { db.close(); resolve(out && out.result !== undefined ? out.result : undefined) }
    t.onerror = () => { db.close(); reject(t.error) }
    // get 类请求在 complete 前即可拿到结果，这里统一在 complete 时 resolve result
  }))
}

export function idbSet(key, val) {
  return tx('readwrite', (s) => s.put(val, key))
}

export function idbGet(key) {
  return new Promise((resolve, reject) => {
    openDB().then((db) => {
      const t = db.transaction(STORE, 'readonly')
      const req = t.objectStore(STORE).get(key)
      req.onsuccess = () => { resolve(req.result === undefined ? null : req.result) }
      req.onerror = () => reject(req.error)
      t.oncomplete = () => db.close()
    }).catch(reject)
  })
}

export function idbDel(key) {
  return tx('readwrite', (s) => s.delete(key))
}

const DEFAULT_TTL = 24 * 60 * 60 * 1000 // 24h

/** 写缓存：存 {data, exp}，默认 24 小时过期 */
export function setCache(key, data, ttlMs = DEFAULT_TTL) {
  return idbSet(key, { data, exp: Date.now() + ttlMs })
}

/** 读缓存：过期或不存在返回 null（过期顺带清除） */
export async function getCache(key) {
  try {
    const row = await idbGet(key)
    if (!row) return null
    if (row.exp && row.exp < Date.now()) {
      idbDel(key).catch(() => {})
      return null
    }
    return row.data
  } catch (e) {
    return null
  }
}
