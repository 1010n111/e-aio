package com.eaio.platform.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 持久层插件装配（P1 册 3.1.2 的 {@code infrastructure/persistence}）。
 *
 * <p>两个内拦截器都是**必需**的，缺一个都会静默降级：
 * <ul>
 *   <li>{@link PaginationInnerInterceptor}：缺了 {@code selectPage} 会把全表当一页返回（分页"没生效"
 *       却不报错，管理页在大表上直接拖垮库）；</li>
 *   <li>{@link OptimisticLockerInnerInterceptor}：缺了 {@code @Version} 不参与 UPDATE，乐观锁形同虚设
 *       （并发改值后写覆盖）。</li>
 * </ul>
 *
 * <p>放在 platform 的 persistence 包内而不是应用壳：它是本模块 Mapper 的持久化插件，多个模块各自声明
 * 互不冲突（MyBatis-Plus 会把容器里所有 {@code Interceptor} 依序装配进 SqlSessionFactory）。
 * 无数据库时本 Bean 仍然存在但不会被用（没有 SqlSessionFactory）。
 */
@Configuration(proxyBeanMethods = false)
public class MybatisPlusConfig {

    /** PostgreSQL 方言（本项目唯一目标库；信创方言抽象见 P1 册 7.5 L9）。 */
    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
