package com.eaio.platform.domain.file;

import java.util.Locale;

/**
 * 文件名与扩展名解析（P1 册 3.3.5 第 2 条的输入侧）：纯函数，无 IO、无 Spring 上下文（6.1 的分层约定）。
 *
 * <p>为什么单独一个类：文件名解析有三个容易各自出错的点——**大小写**（{@code Report.PDF} 必须能通过）、
 * **多段扩展名**（{@code a.tar.gz} 的白名单判据只能是最后一段）、**路径片段**（浏览器/客户端可能把
 * {@code C:\x\y.pdf} 或 {@code ../../y.pdf} 当"文件名"传上来）。三者集中在这里，扩展名白名单与
 * 存储路径生成就不会各解析一遍。
 */
public final class FileNames {

    /** 文件名长度上限：{@code file.original_name} 是 {@code VARCHAR(255)}，超长会让 INSERT 直接失败。 */
    public static final int MAX_NAME_LENGTH = 255;

    /** 磁盘上用不到扩展名时的占位（无扩展名文件）。 */
    public static final String NO_EXTENSION = "";

    private FileNames() {
    }

    /**
     * 取扩展名：**小写、不含点**；无扩展名或扩展名非法（含路径分隔符/超长）返回 {@link #NO_EXTENSION}。
     *
     * <p>取最后一段（{@code a.tar.gz → gz}）：白名单里的 {@code 7z} 这类名字本身不含点，多段扩展名的
     * "从哪一段算"只有最后一个点说得通——否则 {@code a.pdf.exe} 会以 {@code pdf} 的身份过白名单。
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return NO_EXTENSION;
        }
        String name = baseName(fileName);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return NO_EXTENSION;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 扩展名要落 VARCHAR(32)，且不能含路径分隔符/空白（那些只可能来自恶意构造）
        if (ext.length() > 32 || !ext.chars().allMatch(FileNames::isSafeExtensionChar)) {
            return NO_EXTENSION;
        }
        return ext;
    }

    /**
     * 取"基名"：去掉客户端可能带上的目录部分（{@code C:\a\b.pdf} 与 {@code ../../b.pdf} 都只留 {@code b.pdf}）
     * 并过滤 CR/LF。
     *
     * <p>这不是安全边界（落盘路径由服务端用 {@code fileId} 生成，见 {@code LocalFileStorage}），
     * 而是**数据卫生**：原始文件名要进 {@code Content-Disposition}，带 CR/LF 就是响应头注入（3.3.4）。
     */
    public static String baseName(String fileName) {
        if (fileName == null) {
            return NO_EXTENSION;
        }
        String name = fileName.replace('\r', ' ').replace('\n', ' ').trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name;
    }

    /** 落库用文件名：取基名并截到 255（DDL 宽度）；空串退化成一个固定占位，避免 NOT NULL 失败。 */
    public static String normalizedOriginalName(String fileName) {
        String name = baseName(fileName);
        if (name.isEmpty()) {
            return "unnamed";
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private static boolean isSafeExtensionChar(int ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
    }
}
