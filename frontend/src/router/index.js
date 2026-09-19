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
    ],
  },
]

export default createRouter({
  history: createWebHashHistory(),
  routes,
})
