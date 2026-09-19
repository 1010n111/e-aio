/**
 * 领域层：实体、值对象、领域服务与仓储接口（P1 册 2.3）。
 *
 * <p><b>不依赖 Spring Web、不依赖 MyBatis</b>：仓储只声明接口，实现在 {@code infrastructure/persistence}。
 * 纯函数（如参数分级覆盖解析、模板渲染、cron 校验）都放本层，因此可以在模块内单测里 100% 覆盖，
 * 不需要容器与数据库。
 *
 * <p>依赖方向：domain 不依赖 application/infrastructure，也不被其他模块引用。
 */
package com.eaio.platform.domain;
