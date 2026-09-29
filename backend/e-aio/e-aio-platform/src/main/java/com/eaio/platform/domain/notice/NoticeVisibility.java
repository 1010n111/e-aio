package com.eaio.platform.domain.notice;

/** 公告范围匹配的纯函数；缺少上下文时只会匹配 ALL。 */
public final class NoticeVisibility {

    private NoticeVisibility() {
    }

    public static boolean matches(String scopeType, Long targetOrgId, Long targetUserId,
            Long currentUserId, Long currentOrgId) {
        if ("ALL".equals(scopeType)) {
            return true;
        }
        if ("ORG".equals(scopeType)) {
            return targetOrgId != null && targetOrgId.equals(currentOrgId);
        }
        if ("USER".equals(scopeType)) {
            return targetUserId != null && targetUserId.equals(currentUserId);
        }
        return false;
    }
}
