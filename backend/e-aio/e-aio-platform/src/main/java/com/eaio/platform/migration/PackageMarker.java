package com.eaio.platform.migration;

/**
 * 平台支撑模块的包命名空间。
 *
 * <p>P0 阶段本模块只有迁移脚本（{@code src/main/resources/db/migration/platform}），没有 Java 代码；
 * 本类的作用是给模块一个稳定的根包，避免 P1 首个类落地时包名漂移。
 *
 * <p>分类目录：{@code migration} 只放与迁移相关的说明性类型，业务代码（api/application/domain/
 * infrastructure/events）P1 起按五层分包加入。
 */
final class PackageMarker {

    private PackageMarker() {
    }
}
