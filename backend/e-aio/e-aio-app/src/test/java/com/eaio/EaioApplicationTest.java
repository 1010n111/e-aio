package com.eaio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 空应用启动契约：默认 profile 下不需要数据库、不需要 Redis 即可启动。
 *
 * <p>脚本、迁移与外部依赖都属于后续票；本测试守住"骨架可运行"这一条（P0 验收）。
 */
@SpringBootTest
class EaioApplicationTest {

    @Test
    @DisplayName("上下文可加载：唯一启动类 + 默认 profile 无需外部依赖")
    void contextLoads() {
        // 上下文加载失败即测试失败
    }
}
