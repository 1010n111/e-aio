import { action, query } from '@/api/request'

const BASE = '/platform/notice'

export const NOTICE_PERMISSIONS = Object.freeze({
  list: 'platform:notice:list',
  get: 'platform:notice:get',
  add: 'platform:notice:add',
  up: 'platform:notice:up',
  del: 'platform:notice:del',
  publish: 'platform:notice:publish',
})

export function getPage(params = {}) { return query(`${BASE}/GetPage`, { pageNum: 1, pageSize: 20, ...params }) }
export function getUnread() { return query(`${BASE}/GetUnread`, { pageNum: 1, pageSize: 20 }) }
export function add(body, options = {}) { return action(`${BASE}/Add`, body, options) }
export function up(body, options = {}) { return action(`${BASE}/Up`, body, options) }
export function publish(body, options = {}) { return action(`${BASE}/Publish`, body, options) }
export function revoke(id, options = {}) { return action(`${BASE}/Revoke`, { id }, options) }
export function del(id, version, options = {}) { return action(`${BASE}/Del`, { id, version }, options) }
export function markRead(id, options = {}) { return action(`${BASE}/MarkRead`, { id }, options) }
