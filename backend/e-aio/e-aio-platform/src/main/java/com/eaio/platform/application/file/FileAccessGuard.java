package com.eaio.platform.application.file;

import java.util.Optional;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.domain.file.FileMetaFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 文件可见性判定（P1 册 3.3.4 的三条路径 + 2.4.2 的 fail-closed 口径）。
 *
 * <p>判定顺序与放行理由：
 * <ol>
 *   <li><b>上传者本人</b>：{@code uploader_id} 与当前用户相等即放行（不需要任何权限点）；</li>
 *   <li><b>同组织</b>：当前组织 ID 与 {@code uploader_org_id} 相等且都 &gt; 0 即放行——两项都要求大于 0
 *       是刻意的：{@code uploader_org_id = 0} 表示"上传时没有组织上下文"，它不该与"当前也没有组织上下文"
 *       凑成一对相等值而互相放行；</li>
 *   <li><b>管理员</b>：当前认证里带 {@code platform:file:download} 权限即放行（HTTP 面的
 *       {@code @PreAuthorize} 也用同一个权限点，两处必须逐字一致）。</li>
 * </ol>
 *
 * <p><b>{@code OrgContextPort} 缺席时只允许本人或管理员</b>（2.4.2 fail-closed）：iam 未交付时
 * 端口缺席是预期状态，此时**绝不能**退化成"没有组织信息所以放行"——那是静默提权；显式管理员权限
 * 仍可放行。同理，端口在但当前请求没有上下文（定时任务/启动预热）也只允许本人或管理员。
 *
 * <p>判定失败抛 <b>20017</b>（{@code FILE_ACCESS_DENIED}），不是通用的 10403：这个失败**带着文件上下文**
 * （谁在什么时候试图下载哪个文件），是安全事件，要与"你没登录/你没这个权限点"区分开（7.1 的 20017 行）。
 */
@Component
public class FileAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(FileAccessGuard.class);

    private final ObjectProvider<OrgContextPort> orgContextPort;

    public FileAccessGuard(ObjectProvider<OrgContextPort> orgContextPort) {
        this.orgContextPort = orgContextPort;
    }

    /** 当前操作者 ID（可空：无上下文时为 {@code null}，留痕里就记 {@code null}，不伪造 0 号用户）。 */
    public Long currentOperatorId() {
        return currentContext().map(OrgContextPort.OrgContext::userId).orElse(null);
    }

    /** 当前组织 ID（可空）。 */
    public Long currentOrgId() {
        return currentContext().map(OrgContextPort.OrgContext::orgId).orElse(null);
    }

    /**
     * 要求当前请求对 {@code file} 可见；不可见抛 20017。
     *
     * @param file      目标文件行
     * @param permission 管理员的放行权限点（{@link FileParams#PERMISSION_DOWNLOAD}）；
     *                   {@code null} 表示"不接受权限点放行"，只认本人/同组织
     */
    public void requireVisible(FileMetaFile file, String permission) {
        if (canSee(file, permission)) {
            return;
        }
        throw new BusinessException(PlatformErrorCode.FILE_ACCESS_DENIED,
                "无权访问该文件：fileId=" + file.getId());
    }

    /** 是否可见（{@link #requireVisible} 的布尔形态；留痕需要知道"判定失败"本身，故两者都保留）。 */
    public boolean canSee(FileMetaFile file, String permission) {
        if (file == null) {
            return false;
        }
        Optional<OrgContextPort.OrgContext> context = currentContext();
        if (context.isEmpty()) {
            // fail-closed：无组织上下文（port 缺席或本请求没有上下文）时只允许本人或管理员
            Long uploader = file.getUploaderId();
            return (uploader != null && uploader.equals(currentOperatorId())) || hasAuthority(permission);
        }
        long operatorId = context.get().userId();
        if (file.getUploaderId() != null && file.getUploaderId() == operatorId) {
            return true;
        }
        long orgId = context.get().orgId();
        long fileOrgId = file.getUploaderOrgId() == null ? 0L : file.getUploaderOrgId();
        if (orgId > 0L && fileOrgId > 0L && orgId == fileOrgId) {
            return true;
        }
        return hasAuthority(permission);
    }

    /**
     * 当前认证是否带某个权限点：读 Spring Security 的 {@code Authentication}（iam 交付前没有认证链路，
     * 此时为 {@code null}，即"没有权限点"）。
     *
     * <p>为什么不直接依赖 {@code @PreAuthorize}：可见性判定还要在**预签名链接**这条路径上生效，
     * 那条请求同样经过 HTTP 面（所以注解也在），但判定本身属于应用层——把"谁能看这个文件"写在
     * 一处（本类），比让注解与代码各判一半更不容易分叉。
     */
    private static boolean hasAuthority(String permission) {
        if (permission == null || permission.isBlank()) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (permission.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private Optional<OrgContextPort.OrgContext> currentContext() {
        OrgContextPort port = orgContextPort.getIfAvailable();
        if (port == null) {
            return Optional.empty();
        }
        try {
            return port.current();
        } catch (RuntimeException e) {
            // 端口实现抛异常 = 拿不到组织上下文：按"没有上下文"处理（fail-closed），但要留 ERROR
            log.error("读取组织上下文失败，按无上下文处理（只允许本人访问）", e);
            return Optional.empty();
        }
    }
}
