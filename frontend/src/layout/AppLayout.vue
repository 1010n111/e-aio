<template>
  <el-container class="layout">
    <el-header class="layout__header">
      <span class="layout__title">e-aio</span>
      <span class="layout__stage">P0 工程地基 · 前端最小壳</span>
    </el-header>
    <el-main class="layout__main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
// 布局壳：顶栏 + 内容区。侧边菜单、面包屑、标签页属 P1
</script>

<style scoped>
.layout {
  min-height: 100vh;
}

.layout__header {
  display: flex;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid var(--el-border-color-light);
}

.layout__title {
  font-size: 18px;
  font-weight: 600;
}

.layout__stage {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
