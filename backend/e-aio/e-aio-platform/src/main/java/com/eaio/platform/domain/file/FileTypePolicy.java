package com.eaio.platform.domain.file;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.dto.FileUploadCmd;
import com.eaio.platform.application.param.ParamContextProvider;
import com.eaio.platform.application.param.ParamResolver;
import com.eaio.platform.domain.param.ParamContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文件类型策略（P1 册 3.3.5 的信任边界校验，短路顺序即方法内的语句顺序）。
 *
 * <p><b>校验顺序是安全口径，不是风格</b>（3.3.5 逐条）：
 * <ol>
 *   <li>声明大小 &gt; {@code platform.file.max-size} → 20011 <b>早拒绝，不读流</b>：读流才发现超限意味着
 *       白白吃掉一次上传的带宽与磁盘；</li>
 *   <li>扩展名不在 {@code platform.file.allowed-ext} → 20012：以**扩展名白名单**为主判据，
 *       {@code Content-Type} 只作记录（客户端想写什么写什么）；</li>
 *   <li>扩展名大小写统一小写比较（{@code Report.PDF} 必须通过）；</li>
 *   <li>实际字节数为 0 → 20010 由 {@code FileAppService} 在写完流之后判（"声明是 0"与"实际是 0"不同：
 *       声明 0 但流里有内容时，落盘后的实际字节数才是事实）。</li>
 * </ol>
 *
 * <p>参数读取用 {@link ParamContext#systemOnly()}：白名单与上限是**平台级配置**，没有"某组织能传 exe"
 * 这种语义，走组织/用户级覆盖只会让同一次上传在不同组织表现不同。
 *
 * <p>本类**无 IO、无副作用**，只有"读参数 + 判断"（6.1：纯逻辑层不碰 Spring 上下文之外的东西）；
 * 白名单按调用解析，因为 {@code platform.file.allowed-ext} 标了热更新（7.2），缓存住会让管理端改完
 * 白名单还要等重启。
 */
@Component
public class FileTypePolicy {

    /** 单文件大小上限（7.2：默认 52428800 = 50MB）。 */
    public static final String MAX_SIZE_KEY = "platform.file.max-size";

    /** 扩展名白名单（7.2：逗号分隔、小写）。 */
    public static final String ALLOWED_EXT_KEY = "platform.file.allowed-ext";

    /** 上限默认值（与种子里 {@code platform.file.max-size} 的值一致）。 */
    public static final long DEFAULT_MAX_SIZE = 52_428_800L;

    /** 白名单默认值（与种子里 {@code platform.file.allowed-ext} 的值一致）。 */
    public static final String DEFAULT_ALLOWED_EXT =
            "jpg,jpeg,png,gif,webp,bmp,pdf,doc,docx,xls,xlsx,ppt,pptx,txt,csv,zip,7z";

    /** 声明大小未知时的哨兵（{@code -1}）：跳过"早拒绝"，改由流式计数拦（3.3.5 第 4 条）。 */
    private static final long SIZE_UNKNOWN = FileUploadCmd.SIZE_UNKNOWN;

    private static final Logger log = LoggerFactory.getLogger(FileTypePolicy.class);

    private final ParamResolver params;
    private final ParamContextProvider contexts;

    public FileTypePolicy(ParamResolver params, ParamContextProvider contexts) {
        this.params = params;
        this.contexts = contexts;
    }

    /** 单文件上限（字节）；非数字/非正数回落默认值（参数中心读的是人填的字符串）。 */
    public long maxSizeBytes() {
        String raw = params.resolveOrDefault(MAX_SIZE_KEY, String.valueOf(DEFAULT_MAX_SIZE), ParamContext.systemOnly())
                .value();
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_MAX_SIZE;
        } catch (NumberFormatException e) {
            log.warn("参数 {} 不是合法字节数，回落默认值 {}：value={}", MAX_SIZE_KEY, DEFAULT_MAX_SIZE, raw);
            return DEFAULT_MAX_SIZE;
        }
    }

    /** 白名单（小写、去空项、保持声明顺序以便报错时可读）。 */
    public Set<String> allowedExtensions() {
        String raw = params.resolveOrDefault(ALLOWED_EXT_KEY, DEFAULT_ALLOWED_EXT, ParamContext.systemOnly()).value();
        Set<String> allowed = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String ext = item.trim().toLowerCase(Locale.ROOT);
            if (!ext.isEmpty()) {
                allowed.add(ext);
            }
        }
        return allowed;
    }

    /**
     * 扩展名是否在白名单内：白名单项本身就是小写扩展名，逐个精确比较（{@code jpg} 与 {@code jpeg} 是两个
     * 独立项，不是一个模式——7.2 的白名单是清单，不是通配）。
     *
     * <p>无扩展名（{@link FileNames#NO_EXTENSION}）**不允许**：白名单是"允许清单"，不在清单里的一律拒，
     * 没有"没有扩展名所以放行"这一条。
     */
    public boolean isAllowedExtension(String extension) {
        return !FileNames.NO_EXTENSION.equals(extension) && allowedExtensions().contains(extension);
    }

    /**
     * 上传前置校验（短路顺序见类注释）：返回解析出的扩展名给调用方（存储路径要用它）。
     *
     * @throws BusinessException 20011（声明超限）/ 20012（扩展名不在白名单）
     */
    public String validateUpload(FileUploadCmd cmd) {
        long maxSize = maxSizeBytes();
        Long declared = cmd.size();
        if (declared != null && declared != SIZE_UNKNOWN && declared > maxSize) {
            throw new BusinessException(PlatformErrorCode.FILE_TOO_LARGE,
                    "文件超过大小上限：" + declared + " > " + maxSize);
        }
        String extension = FileNames.extensionOf(cmd.fileName());
        if (!isAllowedExtension(extension)) {
            throw new BusinessException(PlatformErrorCode.FILE_TYPE_NOT_ALLOWED,
                    "文件类型不在白名单：" + (extension.isEmpty() ? "(无扩展名)" : extension));
        }
        return extension;
    }
}
