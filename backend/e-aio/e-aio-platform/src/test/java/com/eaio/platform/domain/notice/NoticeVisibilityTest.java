package com.eaio.platform.domain.notice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NoticeVisibilityTest {

    @Test
    void allIsVisibleWithoutContextAndTargetScopesRequireMatchingId() {
        assertThat(NoticeVisibility.matches("ALL", null, null, 9L, 8L)).isTrue();
        assertThat(NoticeVisibility.matches("ORG", 8L, null, 9L, 8L)).isTrue();
        assertThat(NoticeVisibility.matches("ORG", 7L, null, 9L, 8L)).isFalse();
        assertThat(NoticeVisibility.matches("USER", null, 9L, 9L, 8L)).isTrue();
        assertThat(NoticeVisibility.matches("USER", null, 8L, 9L, 8L)).isFalse();
    }
}
