import { action, query } from '@/api/request'

const MONITOR_BASE = '/platform/monitor'
const ALERT_BASE = '/platform/alert'
const RULE_BASE = '/platform/alertRule'

export function metrics() { return query(`${MONITOR_BASE}/Metrics`) }
export function health() { return query(`${MONITOR_BASE}/Health`) }
export function alertPage(params = {}) { return query(`${ALERT_BASE}/GetPage`, { pageNum: 1, pageSize: 50, ...params }) }
export function ack(id) { return action(`${ALERT_BASE}/Ack`, { id }) }
export function resolve(id) { return action(`${ALERT_BASE}/Resolve`, { id }) }
export function rulePage(params = {}) { return query(`${RULE_BASE}/GetPage`, { pageNum: 1, pageSize: 50, ...params }) }
