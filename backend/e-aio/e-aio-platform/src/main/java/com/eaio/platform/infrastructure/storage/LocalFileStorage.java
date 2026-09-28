package com.eaio.platform.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.FileUrlDTO;
import com.eaio.platform.domain.file.FileMetaFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 本地盘存储适配器（P1 册 3.3.2 的默认实现，零外部依赖）。
 *
 * <p>落盘布局：{@code {root}/{yyyy}/{MM}/{dd}/{id%100:02d}/{fileId}.{ext}}，分片目录
 * {@code {root}/chunks/{uploadId}/{index:06d}.part}（分片是 #22 的范围，本票只保证目录布局一致）。
 * 库里的 {@code storage_path} 存的是**相对于 root 的路径**：绝对路径会把行绑死在某台机器上。
 *
 * <p><b>两条安全实现</b>（3.3.5 第 5 条）：
 * <ol>
 *   <li>落盘路径**全部由服务端生成**（{@code fileId} + 白名单扩展名），客户端给的文件名只进
 *       {@code original_name} 列，从不参与拼路径；</li>
 *   <li>每次读写前用 {@code toRealPath()} 校验 {@code candidate.startsWith(root)}：库里的
 *       {@code storage_path} 是被篡改也不会变成任意文件读写的字符串（{@code ../../etc/passwd} 抛异常）。</li>
 * </ol>
 *
 * <p><b>根目录在启动期就绪</b>：不可写时记 ERROR 并在每次上传时以 20015 明确失败（"不允许静默假成功"），
 * 而不是把故障推迟到第一次上传才发现（3.3.2 的启动校验）。
 */
@Component
public class LocalFileStorage implements FileStorage {

    /** 存储类型名（与 DDL 的 CHECK 与 {@code file.storage_type} 逐字一致）。 */
    public static final String TYPE = "LOCAL";

    /** 落盘目录按天分片（3.3.2）：单目录文件数不会无限增长，{@code ls} 与备份都还可用。 */
    private static final DateTimeFormatter DAY_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT);

    /** 天目录下的二级分片：{@code fileId % 100}（3.3.2 逐字）。 */
    private static final int BUCKET_COUNT = 100;

    /** 应用上下文路径默认值（与 {@code application.yml} 的 {@code server.servlet.context-path} 一致）。 */
    private static final String DEFAULT_CONTEXT_PATH = "/api";

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final FileStorageLocation location;
    private final FilePresignTokenService presignTokens;
    private final String contextPath;

    public LocalFileStorage(FileStorageLocation location, FilePresignTokenService presignTokens,
            ApplicationContext applicationContext) {
        this.location = location;
        this.presignTokens = presignTokens;
        this.contextPath = resolveContextPath(applicationContext);
        prepareRoot();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String store(InputStream in, StoredFileMeta meta) {
        String relative = relativePath(meta);
        Path target = resolveInsideRoot(relative);
        createDirectories(target.getParent());
        try {
            Files.copy(in, target);
            if (!Files.isRegularFile(target)) {
                // Files.copy 不抛异常却没落盘（父路径被替换成目录等）：当存储故障处理，绝不返回假成功
                throw new SystemException("文件落盘后不可读（存储故障）：" + relative);
            }
        } catch (IOException e) {
            // 半截文件不留在盘上（3.3.5 第 4 条：超限/IO 失败都要清临时文件）
            deleteQuietly(target);
            throw new SystemException("文件落盘失败：" + relative, e);
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
        return relative;
    }

    @Override
    public InputStream open(String storagePath) {
        try {
            return Files.newInputStream(resolveInsideRoot(requireRelative(storagePath)));
        } catch (IOException e) {
            throw new SystemException("文件读取失败：" + storagePath, e);
        }
    }

    @Override
    public void delete(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        Path path = resolveInsideRoot(storagePath);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new SystemException("删除存储文件失败：" + storagePath, e);
        }
    }

    @Override
    public boolean exists(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return false;
        }
        try {
            return Files.isRegularFile(resolveInsideRoot(storagePath));
        } catch (SystemException e) {
            // 穿越路径不算"存在"：exists 是诊断口，不该把异常抛给清理任务
            log.warn("忽略越界的存储路径（疑似篡改）：storagePath={}", storagePath);
            return false;
        }
    }

    /** 根目录（绝对路径）；删除物理文件后清理空目录时会用到。 */
    public Path root() {
        return location.root();
    }

    @Override
    public FileUrlDTO presignGet(FileMetaFile meta, int expireSeconds) {
        int ttl = FilePresignTokenService.normalizeTtl(expireSeconds);
        FilePresignTokenService.PresignedToken token = presignTokens.sign(meta.getId(), ttl);
        String url = contextPath + "/platform/file/Download?fileId=" + meta.getId()
                + "&exp=" + token.exp() + "&sig=" + token.sig();
        return new FileUrlDTO(meta.getId(), url, Instant.ofEpochSecond(token.exp()), TYPE);
    }

    /** 校验预签名链接：**先比签名，再判时效**（顺序见 {@link FilePresignTokenService}）。 */
    public FilePresignTokenService.PresignVerification verifyPresign(long fileId, String exp, String sig) {
        return presignTokens.verify(fileId, exp, sig);
    }

    /** 相对落盘路径（3.3.2 逐字）：{@code {yyyy}/{MM}/{dd}/{id%100:02d}/{fileId}.{ext}}。 */
    static String relativePath(StoredFileMeta meta) {
        Instant createdAt = meta.createdAt() == null ? Instant.now() : meta.createdAt();
        String day = DAY_DIR.format(createdAt.atZone(ZoneOffset.UTC));
        String bucket = String.format(Locale.ROOT, "%02d", Math.floorMod(meta.fileId(), BUCKET_COUNT));
        String extension = meta.extension() == null || meta.extension().isBlank() ? "" : "." + meta.extension();
        return day + "/" + bucket + "/" + meta.fileId() + extension;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 把相对路径解析成 root 内的绝对路径；越界（含符号链接指向 root 之外）抛 {@link SystemException}。
     *
     * <p>{@code toRealPath()} 在目标不存在时会抛 {@code NoSuchFileException}，因此这里对 absolute 的
     * **父目录**求真实路径后再拼接文件名：写入新文件是常态，读旧文件时父目录一定存在。
     */
    private Path resolveInsideRoot(String relative) {
        Path root = location.root();
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new SystemException("存储路径越界（拒绝访问）：" + relative);
        }
        if (Files.isSymbolicLink(candidate)) {
            throw new SystemException("存储路径符号链接被拒绝：" + relative);
        }
        Path parent = candidate.getParent();
        try {
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(root)) {
                throw new SystemException("存储路径经符号链接越界（拒绝访问）：" + relative);
            }
            return realCandidate;
        } catch (NoSuchFileException e) {
            // 新文件尚不存在：只验证父目录的真实路径，store() 会先创建它。
        } catch (IOException e) {
            throw new SystemException("存储路径无法解析（拒绝访问）：" + relative, e);
        }
        Path realParent = parent == null ? root : realParent(parent, relative);
        Path real = realParent.resolve(candidate.getFileName() == null ? "" : candidate.getFileName().toString());
        if (!real.startsWith(root)) {
            throw new SystemException("存储路径经符号链接越界（拒绝访问）：" + relative);
        }
        return real;
    }

    private Path realParent(Path parent, String relative) {
        try {
            return parent.toRealPath();
        } catch (IOException e) {
            if (Files.isDirectory(parent)) {
                return parent.toAbsolutePath().normalize();
            }
            // 父目录不存在：写入路径由 store() 负责先建目录，这里只做字符串层面的越界校验
            return parent.toAbsolutePath().normalize();
        }
    }

    private String requireRelative(String storagePath) {
        if (storagePath.startsWith("/") || storagePath.contains(":\\") || storagePath.startsWith("\\")) {
            throw new SystemException("存储路径必须是相对路径（拒绝访问）：" + storagePath);
        }
        return storagePath;
    }

    private void prepareRoot() {
        Path root = location.root();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw unusableRoot(root, e);
        }
        if (!Files.isDirectory(root)) {
            throw unusableRoot(root, null);
        }
        if (!Files.isWritable(root)) {
            throw unusableRoot(root, null);
        }
        log.info("文件存储根目录就绪：{}（storageType={}，存储类型记录在文件行上，切换不回改历史行）",
                root, TYPE);
    }

    /**
     * 根目录不可用（建不出来 / 不是目录 / 不可写）→ **拒绝启动**（3.3.2 逐字：不让故障推迟到第一次上传）。
     *
     * <p>这是"不得静默降级"的同一原则在存储上的落点：根目录是文件能力的**必备资源**（不是可选外部依赖），
     * 部署期就能发现的配置错误不该变成"每次上传都 20015"。可移植的单测见
     * {@code LocalFileStorageTest#startupFailsWhenRootIsNotADirectory}（把 root 指向一个已存在的普通文件）。
     */
    private static SystemException unusableRoot(Path root, IOException cause) {
        String reason = Files.isDirectory(root) ? "不可写" : "不是目录（或无法创建）";
        String message = "文件存储根目录" + reason + "，拒绝启动：root=" + root
                + "（配置项 platform.file.local-root / platform.file.parameters，见 P1 册 3.3.2）";
        return cause == null ? new SystemException(message) : new SystemException(message, cause);
    }

    private void createDirectories(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new SystemException("创建存储目录失败：" + dir, e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 删除失败要留 ERROR：它意味着孤儿清理删不掉文件（磁盘会慢慢涨）
            log.error("删除存储文件失败：{}", path, e);
        }
    }

    /** 上下文路径（{@code /api}）：从 Spring 环境取，取不到（或非 Spring 容器）时用约定的默认值。 */
    private static String resolveContextPath(ApplicationContext applicationContext) {
        try {
            Object value = applicationContext.getEnvironment().getProperty("server.servlet.context-path");
            if (value == null || String.valueOf(value).isBlank()) {
                return DEFAULT_CONTEXT_PATH;
            }
            String path = String.valueOf(value).trim();
            return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        } catch (RuntimeException e) {
            return DEFAULT_CONTEXT_PATH;
        }
    }
}
