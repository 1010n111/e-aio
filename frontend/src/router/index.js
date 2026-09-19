import { createRouter, createWebHashHistory } from 'vue-router'

import AppLayout from '@/layout/AppLayout.vue'

/**
 * 路由壳（P0）：只有布局与两个占位页。
 *
 * 动态菜单、权限路由、字典与水印属 P1；这里刻意不引入任何业务页面，
 * 也不预生成"假菜单"——空壳就是空壳，免得 P1 分不清哪些是真实能力。
 */
const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: AppLayout,
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
      },
      {
        path: 'platform/param',
        name: 'platform-param',
        component: () => import('@/views/platform/param/ParamList.vue'),
        meta: {
          title: '参数管理',
          // 权限点在路由声明，按钮级隐藏等 iam 下发权限码后由 v-hasPermi 接管（P1 册 3.10.2、7.5 L1）
          permission: 'platform:param:list',
        },
      },
      {
        path: 'platform/dict',
        name: 'platform-dict',
        component: () => import('@/views/platform/dict/DictList.vue'),
        meta: {
          title: '字典管理',
          // 同 3.10.2：M1–M2 页面按钮不做前端隐藏，权限由后端 @PreAuthorize 兜底（7.5 遗留）
          permission: 'platform:dict:list',
        },
      },
    ],
  },
]

export default createRouter({
  history: createWebHashHistory(),
  routes,
})
