<template>
  <div class="login">
    <el-card
      class="login__card"
      shadow="never"
    >
      <h2 class="login__title">e-aio</h2>
      <el-alert
        type="warning"
        :closable="false"
        title="P0 无认证实现，本页只是登录态占位"
        description="后端在 P0 不引入 Spring Security 与 JWT（见 P0 册 3.3），登录接口与令牌刷新在 P1 由 iam 落地。"
      />
      <el-button
        class="login__button"
        type="primary"
        @click="handlePlaceholderLogin"
      >
        以占位方式进入
      </el-button>
    </el-card>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

import { markLoggedIn } from '@/auth/session'

const router = useRouter()

// 占位登录：只把"已登录"标记立起来，不调后端、不发令牌——P1 换成真实登录接口
function handlePlaceholderLogin() {
  markLoggedIn()
  router.push('/')
}
</script>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
}

.login__card {
  width: 420px;
}

.login__title {
  margin: 0 0 12px;
}

.login__button {
  margin-top: 16px;
  width: 100%;
}
</style>
