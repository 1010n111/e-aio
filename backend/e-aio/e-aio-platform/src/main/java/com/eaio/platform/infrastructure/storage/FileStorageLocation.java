package com.eaio.platform.infrastructure.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.eaio.platform.application.file.FileParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地盘根目录（P1 册 3.3.2）：默认 {@code ${user.home}/.eaio/files}。
 *
 * <p><b>启动期解析一次并缓存</b>：根目录是"重启生效"的资源（7.2 给 {@code platform.file.local-root} 标的是
 * 热更新"否"）——根目录换了意味着已落盘的文件按新根目录找不到，运行期换根比不换更危险。
 *
 * <p><b>为什么读 Spring 属性而不是参数中心</b>（与设计册 3.3.2 的差异，登记在《实现注记（T9）》）：
 * 根目录必须在**迁移完成之前**就确定——{@code LocalFileStorage} 的启动校验是构造期行为，而参数中心读值
 * 要连库（此时 {@code eaio_platform.param} 可能还没建）。实测代价：从参数中心读会让整个应用的上下文启动
 * 依赖"迁移已跑完"这一顺序，比"配置在部署面"贵得多。参数中心的 {@code platform.file.local-root}
 * 行保持登记（运维可查），部署侧以本属性为准。
 *
 * <p><b>占位符展开</b>：默认值里带 {@code ${user.home}}（种子与文档都这么写），这里展开 {@code ${user.home}}
 * 与 {@code ~}；未展开的字符串会被当成相对路径，落盘位置取决于进程工作目录，是最难查的一类
 * "文件写到别处去了"。
 *
 * <p>根目录在这里只解析不做可写校验：可写校验与"拒绝启动"在 {@link LocalFileStorage} 的构造期做
 * （一处判据，避免两处口径）。
 */
@Component
public class FileStorageLocation {

    private static final Logger log = LoggerFactory.getLogger(FileStorageLocation.class);

    private final Path root;

    public FileStorageLocation(@Value("${eaio.file.local-root:" + FileParams.DEFAULT_LOCAL_ROOT + "}") String root) {
        this.root = normalize(root);
    }

    /** 根目录（绝对、规范化、**已是符号链接的真实路径**）：越界校验必须与之一致，否则 macOS 的
     * {@code /tmp → /private/tmp} 这类软链会让合法路径被判越界。 */
    public Path root() {
        return root;
    }

    private static Path normalize(String configured) {
        String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) {
            value = FileParams.DEFAULT_LOCAL_ROOT;
        }
        value = value.replace("${user.home}", System.getProperty("user.home", "."));
        if (value.startsWith("~")) {
            value = System.getProperty("user.home", ".") + value.substring(1);
        }
        Path path = Paths.get(value).toAbsolutePath().normalize();
        try {
            // 根目录通常还不存在：先建出来再取真实路径，越界比较才不会被软链干扰。
            // 建不出来**不要在这里报错**（比如已存在同名普通文件）——交给 LocalFileStorage 的启动校验统一
            // 拒绝启动，免得"目录不可用"有两处口径、两处日志。
            Files.createDirectories(path);
            return path.toRealPath();
        } catch (IOException e) {
            log.warn("根目录真实路径解析失败（按规范化路径继续，稍后由启动校验拒绝启动）：{}", path, e);
            return path;
        }
    }
}
