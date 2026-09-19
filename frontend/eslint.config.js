import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'

// P0 最小规则集：Vue 官方推荐 + JS 推荐，够用即止（不做风格争论）
export default [
  { ignores: ['dist/**', 'node_modules/**', 'coverage/**'] },
  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.{js,vue}'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      globals: {
        window: 'readonly',
        document: 'readonly',
        localStorage: 'readonly',
        crypto: 'readonly',
      },
    },
    rules: {
      'vue/multi-word-component-names': 'off',
      // 模板里短文本（<h2>e-aio</h2>）换行只是噪音，收益为零
      'vue/singleline-html-element-content-newline': 'off',
    },
  },
  {
    files: ['vite.config.js', 'eslint.config.js'],
    languageOptions: {
      globals: { process: 'readonly' },
    },
  },
]
