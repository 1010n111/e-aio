import { action, download } from '@/api/request'

const BASE = '/platform/excelTask'

export const EXCEL_TASK_PERMISSIONS = Object.freeze({
  list: 'platform:excelTask:list',
  get: 'platform:excelTask:get',
  export: 'platform:excelTask:export',
  import: 'platform:excelTask:import',
  download: 'platform:excelTask:download',
  cancel: 'platform:excelTask:cancel',
})

export const EXCEL_CODES = Object.freeze({
  ROW_LIMIT: 20033,
  CONFLICT: 20034,
  NOT_FOUND: 20035,
  QUEUE_FULL: 20036,
  HANDLER_MISSING: 20037,
})

export const excelTask = {
  import(payload) {
    return action(`${BASE}/Import`, payload)
  },
  export(payload) {
    return action(`${BASE}/Export`, payload)
  },
  get(taskId) {
    return action(`${BASE}/Get`, { taskId })
  },
  getPage(params = {}) {
    return action(`${BASE}/GetPage`, { pageNum: 1, pageSize: 20, ...params })
  },
  progress(taskId) {
    return action(`${BASE}/GetProgress`, { taskId })
  },
  errors(taskId, pageNum = 1, pageSize = 20) {
    return action(`${BASE}/GetErrorPage`, { taskId, pageNum, pageSize })
  },
  cancel(taskId) {
    return action(`${BASE}/Cancel`, { taskId })
  },
  downloadResult(taskId) {
    return download(`${BASE}/DownloadResult`, { taskId })
  },
  downloadErrors(taskId) {
    return download(`${BASE}/DownloadErrors`, { taskId })
  },
}
