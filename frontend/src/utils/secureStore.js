// AES-GCM 本地加密存储（WebCrypto）
// 密钥首次生成后 raw 导出 base64 存 IndexedDB（key: ll_aes_key），密文写 localStorage。
// 用途：编辑草稿等“不希望明文落盘”的本地数据。

import { idbGet, idbSet } from './idb'

const KEY_IDB = 'll_aes_key'
let keyPromise = null

function b64encode(buf) {
  const bytes = new Uint8Array(buf)
  let bin = ''
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i])
  return btoa(bin)
}

function b64decode(b64) {
  const bin = atob(b64)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return bytes
}

/** 获取（或首次生成）AES-GCM 密钥 */
function getKey() {
  if (!keyPromise) {
    keyPromise = (async () => {
      try {
        const saved = await idbGet(KEY_IDB)
        if (saved) {
          return await crypto.subtle.importKey('raw', b64decode(saved), { name: 'AES-GCM' }, true, ['encrypt', 'decrypt'])
        }
      } catch (e) { /* 读取失败则重新生成 */ }
      const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt'])
      const raw = await crypto.subtle.exportKey('raw', key)
      try { await idbSet(KEY_IDB, b64encode(raw)) } catch (e) { /* 持久化失败仅影响下次会话 */ }
      return key
    })()
  }
  return keyPromise
}

/** 加密对象写入 localStorage；失败返回 false */
export async function secureSet(key, obj) {
  try {
    const k = await getKey()
    const iv = crypto.getRandomValues(new Uint8Array(12))
    const plain = new TextEncoder().encode(JSON.stringify(obj))
    const cipher = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, k, plain)
    // base64(iv + cipher)
    const pack = new Uint8Array(iv.length + cipher.byteLength)
    pack.set(iv, 0)
    pack.set(new Uint8Array(cipher), iv.length)
    localStorage.setItem(key, b64encode(pack.buffer))
    return true
  } catch (e) {
    return false
  }
}

/** 读取并解密；不存在或解密失败返回 null */
export async function secureGet(key) {
  try {
    const b64 = localStorage.getItem(key)
    if (!b64) return null
    const pack = b64decode(b64)
    const iv = pack.slice(0, 12)
    const cipher = pack.slice(12)
    const k = await getKey()
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, k, cipher)
    return JSON.parse(new TextDecoder().decode(plain))
  } catch (e) {
    return null
  }
}

export function secureDel(key) {
  localStorage.removeItem(key)
}
