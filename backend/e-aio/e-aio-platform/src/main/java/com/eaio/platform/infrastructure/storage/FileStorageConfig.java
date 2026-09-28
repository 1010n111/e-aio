package com.eaio.platform.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存储的部署期配置（P1 册 3.3.2；登记项见《实现注记（T9）》）。
 *
 * <p>与设计册 3.3.2 的差异：那里把 {@code platform.file.storage-type} 写在参数中心，这里落到
 * {@code eaio.file.storage-type} 部署属性上，理由见 {@link FileStorageConfig}。
 *
 * <p>{@code storageType} 只决定**新写**的文件走哪个适配器；**读**哪个适配器永远由文件行上的
 * {@code storage_type} 决定（裁决 P1-C5）——切换配置不会让历史文件读不到。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileStorageConfig.EaioFileProperties.class)
public class FileStorageConfig {

    /** {@code eaio.file.*} 前缀的配置。 */
    @ConfigurationProperties(prefix = "eaio.file")
    public record EaioFileProperties(String storageType) {

        /** 生效的存储类型（未配置时为 {@code LOCAL}）。 */
        public String storageTypeOrDefault() {
            return storageType == null || storageType.isBlank() ? LocalFileStorage.TYPE : storageType.trim();
        }
    }
}
