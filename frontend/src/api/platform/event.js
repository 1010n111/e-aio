import { action, query } from '@/api/request'

const BASE = '/platform/eventDelivery'

export function deadLetters(params = {}) {
  return query(`${BASE}/GetPage`, { status: 'DEAD', pageNum: 1, pageSize: 50, ...params })
}

export function replay(eventId) {
  return action(`${BASE}/Replay`, { eventId })
}
