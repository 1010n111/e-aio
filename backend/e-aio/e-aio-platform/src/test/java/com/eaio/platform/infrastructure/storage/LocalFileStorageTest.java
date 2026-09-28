package com.eaio.platform.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.eaio.common.exception.SystemException;
import com.eaio.platform.api.dto.FileUrlDTO;
import com.eaio.platform.domain.file.FileMetaFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.StaticApplicationContext;

/**
 * 本地盘存储适配器的测试（P1 册 3.3.9 第 2 行 + 3.3.5 第 5 条）。
 *
 * <p>根目录用 {@link TempDir}（真实文件系统，跨平台），只有 {@code FileParams} 是替身——它唯一的职责是
 * "给出根目录字符串"，替身让测试不必拉起参数中心。
 */
class LocalFileStorageTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private static LocalFileStorage storage(Path root) {
        FileStorageLocation location = new FileStorageLocation(root.toString());
        FilePresignTokenService tokens = new FilePresignTokenService(SECRET);
        return new LocalFileStorage(location, tokens, new StaticApplicationContext());
    }

    private static StoredFileMeta meta(long fileId, String extension) {
        return new StoredFileMeta(fileId, extension, "text/plain", java.time.Instant.parse("2026-03-04T05:06:07Z"));
    }

    @Test
    @DisplayName("store 后 open：字节完全一致，且相对路径在 root 内、按 3.3.2 的布局生成")
    void storeThenOpenReturnsSameBytes(@TempDir Path root) throws IOException {
        LocalFileStorage storage = storage(root);
        byte[] content = "hello 文件中心".getBytes(StandardCharsets.UTF_8);

        String path = storage.store(new ByteArrayInputStream(content), meta(12345L, "txt"));

        assertThat(path).isEqualTo("2026/03/04/45/12345.txt");
        assertThat(root.resolve(path)).exists();
        try (InputStream in = storage.open(path)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        assertThat(storage.exists(path)).isTrue();
    }

    @Test
    @DisplayName("无扩展名：落盘文件名不带点（{fileId} 本身）")
    void storeWithoutExtension(@TempDir Path root) {
        LocalFileStorage storage = storage(root);

        String path = storage.store(new ByteArrayInputStream(new byte[] {1, 2, 3}), meta(7L, ""));

        assertThat(path).isEqualTo("2026/03/04/07/7");
        assertThat(storage.exists(path)).isTrue();
    }

    @Test
    @DisplayName("路径穿越：../../etc/passwd 与绝对路径都被拒（open/delete/exists 三条路）")
    void rejectsTraversal(@TempDir Path root) {
        LocalFileStorage storage = storage(root);

        assertThatThrownBy(() -> storage.open("../../etc/passwd")).isInstanceOf(SystemException.class);
        assertThatThrownBy(() -> storage.open("/etc/passwd")).isInstanceOf(SystemException.class);
        assertThatThrownBy(() -> storage.delete("../outside.txt")).isInstanceOf(SystemException.class);
        assertThat(storage.exists("../../../outside.txt"))
                .as("exists 是诊断口：越界返回 false 而不是抛异常")
                .isFalse();
    }

    @Test
    @DisplayName("物理删除失败必须抛存储异常，让清理任务保留库行待重试")
    void deleteFailureIsReported(@TempDir Path root) throws IOException {
        LocalFileStorage storage = storage(root);
        Files.createDirectories(root.resolve("blocked"));
        Files.writeString(root.resolve("blocked/child.txt"), "still referenced");

        assertThatThrownBy(() -> storage.delete("blocked"))
                .isInstanceOf(SystemException.class);
    }

    @Test
    @DisplayName("符号链接越界：root 内的软链指向外部时同样拒绝（无创建权限的环境跳过）")
    void rejectsSymlinkEscape(@TempDir Path root) throws IOException {
        LocalFileStorage storage = storage(root);
        Path outside = Files.createTempFile("eaio-outside", ".txt");
        try {
            Path link = root.resolve("link.txt");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (IOException | UnsupportedOperationException e) {
                // Windows 未开启开发者模式/非管理员时创建软链会被拒（客户端没有所需的特权）：
                // 这是环境能力缺失，不是被测逻辑的问题——跳过而不是把测试改成"永远绿"
                Assumptions.abort("本环境无法创建符号链接，跳过越界校验：" + e.getMessage());
            }
            assertThatThrownBy(() -> storage.open("link.txt")).isInstanceOf(SystemException.class);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @DisplayName("启动校验：根目录指向已存在的普通文件 → 构造期拒绝（不是推迟到第一次上传）")
    void startupFailsWhenRootIsNotADirectory(@TempDir Path temp) throws IOException {
        Path file = Files.createFile(temp.resolve("not-a-dir"));

        assertThatThrownBy(() -> storage(file))
                .as("3.3.2：根目录不可用必须拒绝启动")
                .isInstanceOf(SystemException.class)
                .hasMessageContaining("拒绝启动");
    }

    @Test
    @DisplayName("delete 幂等；preSignGet 给同源带签名链接（fileId/exp/sig 三要素齐）")
    void deleteIsIdempotentAndPresignBuildsUrl(@TempDir Path root) {
        LocalFileStorage storage = storage(root);
        String path = storage.store(new ByteArrayInputStream(new byte[] {9}), meta(3L, "pdf"));

        storage.delete(path);
        storage.delete(path);
        assertThat(storage.exists(path)).isFalse();

        FileMetaFile row = new FileMetaFile();
        row.setId(3L);
        row.setStorageType(LocalFileStorage.TYPE);
        row.setStoragePath(path);
        FileUrlDTO url = storage.presignGet(row, 600);

        assertThat(url.fileId()).isEqualTo(3L);
        assertThat(url.storageType()).isEqualTo(LocalFileStorage.TYPE);
        assertThat(url.url())
                .startsWith("/api/platform/file/Download?fileId=3&exp=")
                .contains("&sig=");
        assertThat(url.expireAt()).isAfter(java.time.Instant.now());
    }
}
