package com.eaio.platform.application.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.domain.file.FileMetaFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 文件可见性判定的测试（P1 册 3.3.4 的三条路径 + 2.4.2 的 fail-closed）。
 *
 * <p>票面验收第 3 条"身份上下文缺席时仅上传者或管理员能下载"就在这里钉住：iam 未交付（端口缺席）时
 * 仍按权限点放行管理员——**绝不能**退化成"没有组织信息所以放行"。
 */
class FileAccessGuardTest {

    private static final String PERMISSION = "platform:file:download";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private static FileAccessGuard guard(OrgContextPort port) {
        ObjectProvider<OrgContextPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        return new FileAccessGuard(provider);
    }

    private static OrgContextPort port(long orgId, long userId) {
        return () -> Optional.of(new OrgContextPort.OrgContext(orgId, userId));
    }

    private static FileMetaFile file(Long uploaderId, long uploaderOrgId) {
        FileMetaFile row = new FileMetaFile();
        row.setId(1L);
        row.setUploaderId(uploaderId);
        row.setUploaderOrgId(uploaderOrgId);
        return row;
    }

    @Test
    @DisplayName("端口缺席（iam 未交付）：本人或管理员放行，其他人 20017")
    void absentPortAllowsUploaderOrAdmin() {
        FileAccessGuard guard = guard(null);
        FileMetaFile own = file(null, 0L);

        assertThat(guard.currentOperatorId()).isNull();
        assertThatThrownBy(() -> guard.requireVisible(own, PERMISSION))
                .as("上传者未知 + 无上下文 = 不可见（fail-closed）")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(PlatformErrorCode.FILE_ACCESS_DENIED.getCode()));

        // 上传者是 0（历史数据/系统导入）同理：不做"0 号用户"的隐式放行
        assertThatThrownBy(() -> guard.requireVisible(file(0L, 0L), PERMISSION))
                .isInstanceOf(BusinessException.class);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ops", "n/a",
                        List.of(new SimpleGrantedAuthority(PERMISSION))));
        assertThatCode(() -> guard.requireVisible(file(8L, 20L), PERMISSION))
                .as("端口缺席时，显式管理员权限仍可放行").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("端口在：本人放行；同组织放行；两者都不是时看权限点")
    void portPresentAllowsSelfSameOrgOrPermission() {
        FileAccessGuard guard = guard(port(10L, 7L));

        assertThatCode(() -> guard.requireVisible(file(7L, 99L), PERMISSION))
                .as("上传者本人放行（不需要任何权限点）").doesNotThrowAnyException();
        assertThatCode(() -> guard.requireVisible(file(8L, 10L), PERMISSION))
                .as("同组织放行").doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireVisible(file(8L, 20L), PERMISSION))
                .as("既非本人也非同组织，且没有权限点").isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("组织比较：0 与 0 不算同组织（'没有组织上下文'不该互相放行）")
    void zeroOrgDoesNotMatchZeroOrg() {
        FileAccessGuard guard = guard(port(0L, 7L));

        assertThatThrownBy(() -> guard.requireVisible(file(8L, 0L), PERMISSION))
                .as("双方都没有组织上下文时不放行，只有权限点能放行")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("权限点放行：认证里带 platform:file:download 才放行（字符串逐字比较）")
    void grantsByAuthority() {
        FileAccessGuard guard = guard(port(10L, 7L));
        FileMetaFile foreign = file(8L, 20L);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ops", "n/a",
                        List.of(new SimpleGrantedAuthority("platform:file:list"))));
        assertThatThrownBy(() -> guard.requireVisible(foreign, PERMISSION))
                .as("相近但不相同的权限点必须不放行").isInstanceOf(BusinessException.class);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ops", "n/a",
                        List.of(new SimpleGrantedAuthority(PERMISSION))));
        assertThatCode(() -> guard.requireVisible(foreign, PERMISSION)).doesNotThrowAnyException();

        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> guard.requireVisible(foreign, null))
                .as("不允许权限点放行时（permission = null）只认本人/同组织")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("当前请求无上下文（定时任务/预热）：退化为本人或管理员")
    void emptyContextFallsBackToSelfOrAdmin() {
        FileAccessGuard guard = guard(() -> Optional.empty());

        assertThat(guard.currentOrgId()).isNull();
        assertThatThrownBy(() -> guard.requireVisible(file(8L, 10L), PERMISSION))
                .isInstanceOf(BusinessException.class);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("ops", "n/a",
                        List.of(new SimpleGrantedAuthority(PERMISSION))));
        assertThatCode(() -> guard.requireVisible(file(8L, 10L), PERMISSION))
                .as("无组织上下文时，显式管理员权限仍可放行").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("端口实现抛异常：按无上下文处理（fail-closed）而不是把 500 抛给调用方")
    void portFailureIsFailClosed() {
        FileAccessGuard guard = guard(() -> {
            throw new IllegalStateException("iam 适配器故障");
        });

        assertThat(guard.canSee(file(8L, 10L), PERMISSION)).isFalse();
        assertThat(guard.canSee(file(0L, 0L), PERMISSION)).isFalse();
    }
    @Test
    @DisplayName("file 为 null：不可见（调用方应先抛 20014）")
    void nullFileIsNeverVisible() {
        assertThat(guard(port(10L, 7L)).canSee(null, PERMISSION)).isFalse();
    }
}
