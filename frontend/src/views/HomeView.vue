<template>
  <el-card shadow="never">
    <template #header>工程状态</template>
    <el-descriptions
      :column="1"
      border
    >
      <el-descriptions-item label="后端">Spring Boot 4.1.x · Spring Modulith（模块化单体）</el-descriptions-item>
      <el-descriptions-item label="前端">Vue 3 + Vite + Element Plus（P0 最小壳）</el-descriptions-item>
      <el-descriptions-item label="接口契约">
        统一 POST + JSON，HTTP 恒 200，业务结果看响应体 code（ADR-0001）
      </el-descriptions-item>
      <el-descriptions-item label="当前批次">P0 工程地基：骨架、契约、迁移、工具门面、前端壳</el-descriptions-item>
    </el-descriptions>
    <el-alert
      class="home__notice"
      type="info"
      :closable="false"
      title="本页是工程状态占位页，不是业务页面"
      description="业务模块（platform / iam / audit …）在 P1 起逐步落地；动态菜单、权限指令、字典与水印同样留 P1。"
    />
  </el-card>
</template>

<script setup>
// 占位页：只陈述工程状态，不展示任何假数据
</script>

<style scoped>
.home__notice {
  margin-top: 16px;
}
</style>
