import { action, query } from '@/api/request'

const BASE = '/platform/notifyTemplate'

export const NOTIFY_TEMPLATE_PERMISSIONS = Object.freeze({
  list: 'platform:notifyTemplate:list',
  get: 'platform:notifyTemplate:get',
  save: 'platform:notifyTemplate:save',
  del: 'platform:notifyTemplate:del',
})

export function getPage(params = {}) { return query(`${BASE}/GetPage`, { pageNum: 1, pageSize: 20, ...params }) }
export function save(body, options = {}) { return action(`${BASE}/Save`, body, options) }
export function del(templateCode, version, options = {}) { return action(`${BASE}/Del`, { templateCode, version }, options) }
export function render(templateCode, params = {}) { return query(`${BASE}/Render`, { templateCode, params }) }
