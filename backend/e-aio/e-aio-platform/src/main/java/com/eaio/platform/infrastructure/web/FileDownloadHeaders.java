package com.eaio.platform.infrastructure.web;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 下载响应头（P1 册 3.3.4 的"防注入与缓存泄漏"四条，集中一处生成）。
 *
 * <p>为什么单独一个类而不是在控制器里拼字符串：
 * <ol>
 *   <li><b>响应头注入</b>：{@code Content-Disposition} 里要放**用户提供的**文件名，带 CR/LF 就能插进任意头
 *       （甚至伪造响应体）。这里先滤换行再截断到 200 字符，最后才 percent-encode；</li>
 *   <li><b>缓存泄漏</b>：文件内容带权限语义，任何中间缓存记住它都是越权面。{@code private, no-store} 是硬要求；</li>
 *   <li><b>MIME 嗅探</b>：库里的 {@code content_type} 是客户端上传时声明的（不可信），浏览器按它渲染 HTML
 *       就等于拿到了一个存储型 XSS 的落点。{@code nosniff} 强制浏览器按声明的类型处理。</li>
 * </ol>
 *
 * <p>编码用 RFC 5987 的 {@code filename*=UTF-8''<percent-encoded>}：只有它能无损承载中文文件名；
 * 同时给一个去掉非 ASCII 的 {@code filename=} 兜底，供老客户端识别。
 */
public final class FileDownloadHeaders {

    /** 文件名截断长度（3.3.8：截到 200 字符后再编码）。 */
    static final int MAX_FILENAME_LENGTH = 200;

    /** 无扩展名/类型未知时的兜底类型：二进制流，让浏览器直接存盘而不是尝试内联渲染。 */
    static final String FALLBACK_CONTENT_TYPE = MediaType.APPLICATION_OCTET_STREAM_VALUE;

    private FileDownloadHeaders() {
    }

    /** 按库里的类型与净化后的文件名生成响应头集合。 */
    public static HttpHeaders of(String contentType, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, safeContentType(contentType));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(filename));
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return headers;
    }

    /**
     * {@code Content-Disposition: attachment; filename*=UTF-8''<percent-encoded>}（3.3.4 逐字）。
     *
     * <p>文件名先过"滤换行 + 截断"，再编码；编码后不可能再出现 CR/LF（percent-encoding 把它们变成 %0D/%0A，
     * 那是**字面量**而非控制字符——头里安全，浏览器解码后得到的也是无害字符）。
     */
    public static String contentDisposition(String filename) {
        String safe = sanitize(filename);
        return "attachment; filename=\"" + asciiFallback(safe) + "\"; filename*=UTF-8''" + percentEncode(safe);
    }

    /**
     * 净化文件名：去 CR/LF（含 Unicode 行分隔符，某些代理会按它们断行）、去引号与反斜杠（会截断/转义
     * {@code filename="..."} 的值）、截断到 200 字符。
     */
    static String sanitize(String filename) {
        String name = filename == null ? "" : filename;
        name = name.replace('\r', ' ').replace('\n', ' ')
                .replace('\u2028', ' ').replace('\u2029', ' ')
                .replace('"', '\'').replace('\\', '_')
                .trim();
        if (name.isEmpty()) {
            name = "download";
        }
        return name.length() > MAX_FILENAME_LENGTH ? name.substring(0, MAX_FILENAME_LENGTH) : name;
    }

    /** 老式 {@code filename=} 的值：只保留可打印 ASCII，其余换成下划线（它本来就是兜底，不必无损）。 */
    static String asciiFallback(String safeName) {
        StringBuilder builder = new StringBuilder(safeName.length());
        for (int i = 0; i < safeName.length(); i++) {
            char ch = safeName.charAt(i);
            builder.append(ch >= 0x20 && ch < 0x7F ? ch : '_');
        }
        return builder.toString();
    }

    /** RFC 5987 的 percent-encoding：非 {@code attr-char} 一律按 UTF-8 逐字节编码。 */
    static String percentEncode(String value) {
        StringBuilder builder = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = b & 0xFF;
            if (isAttrChar(unsigned)) {
                builder.append((char) unsigned);
            } else {
                builder.append('%').append(String.format("%02X", unsigned));
            }
        }
        return builder.toString();
    }

    /** RFC 5987 attr-char：{@code ALPHA / DIGIT / !#$&+-.^_`|~}。 */
    private static boolean isAttrChar(int ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
            return true;
        }
        return ch == '!' || ch == '#' || ch == '$' || ch == '&' || ch == '+' || ch == '-'
                || ch == '.' || ch == '^' || ch == '_' || ch == '`' || ch == '|' || ch == '~';
    }

    /** 库里的类型：空白即未知，用二进制兜底（**绝不能**回落到 JSON，前端会把它当成错误响应）。 */
    static String safeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? FALLBACK_CONTENT_TYPE : contentType.trim();
    }
}
