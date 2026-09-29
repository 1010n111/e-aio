package com.eaio.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.NotifyTemplateApi;
import com.eaio.platform.api.dto.NoticeDTO;
import com.eaio.platform.api.dto.NoticePublishCmd;
import com.eaio.platform.api.dto.NotifyTemplateSaveCmd;
import com.eaio.platform.api.port.OrgContextPort;
import com.eaio.platform.application.NoticeAppService;
import com.eaio.platform.application.NotifyTemplateAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** #21：公告范围、定时发布、过期与模板渲染的真实数据库验收。 */
@SpringBootTest
class NoticeIT extends IntegrationTestBase {

    private static final long ORG_ID = 9300L;
    private static final long USER_ID = 9301L;

    @TestConfiguration
    static class OrgContextStub {
        static final AtomicReference<OrgContextPort.OrgContext> CURRENT = new AtomicReference<>();

        @Bean
        OrgContextPort orgContextPort() {
            return () -> Optional.ofNullable(CURRENT.get());
        }
    }

    @Autowired
    private NoticeAppService notices;
    @Autowired
    private NotifyTemplateAppService templates;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void signIn() {
        OrgContextStub.CURRENT.set(new OrgContextPort.OrgContext(ORG_ID, USER_ID));
    }

    @Test
    @DisplayName("组织/用户范围只对命中者可见，ALL 不写收件行，重复已读不重复落库")
    void scopedUnreadAndIdempotentRead() {
        String suffix = UUID.randomUUID().toString();
        NoticeDTO all = notices.save(new NoticePublishCmd(null, "all-" + suffix, "all", "ALL", null, null,
                Instant.now(), null, false));
        NoticeDTO org = notices.save(new NoticePublishCmd(null, "org-" + suffix, "org", "ORG", "ORG",
                List.of(ORG_ID), Instant.now(), null, true));
        NoticeDTO user = notices.save(new NoticePublishCmd(null, "user-" + suffix, "user", "USER", "USER",
                List.of(USER_ID), Instant.now(), null, false));

        List<NoticeDTO> unread = notices.unread().getRecords();
        assertThat(unread).extracting(NoticeDTO::id).contains(all.id(), org.id(), user.id());
        assertThat(unread.get(0).id()).isEqualTo(org.id());
        assertThat(jdbc.queryForObject("select count(*) from eaio_platform.notice_target where notice_id = ?",
                Integer.class, all.id())).isZero();

        notices.markRead(org.id());
        notices.markRead(org.id());
        assertThat(jdbc.queryForObject("select count(*) from eaio_platform.notice_read "
                + "where notice_id = ? and user_id = ?", Integer.class, org.id(), USER_ID)).isEqualTo(1);

        OrgContextStub.CURRENT.set(new OrgContextPort.OrgContext(ORG_ID + 1, USER_ID + 1));
        assertThat(notices.unread().getRecords()).extracting(NoticeDTO::id)
                .doesNotContain(org.id(), user.id());
        assertThatThrownBy(() -> notices.markRead(org.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(20044));
    }

    @Test
    @DisplayName("未来发布时间先保持草稿，到点扫描发布，过期后不再出现在未读")
    void scheduledAndExpiredNotice() {
        String suffix = UUID.randomUUID().toString();
        Instant future = Instant.now().plusSeconds(3600);
        NoticeDTO draft = notices.save(new NoticePublishCmd(null, "future-" + suffix, "future", "ALL", null, null,
                future, null, false));
        assertThat(draft.publishStatus()).isEqualTo("DRAFT");
        assertThat(notices.unread().getRecords()).extracting(NoticeDTO::id).doesNotContain(draft.id());

        jdbc.update("update eaio_platform.notice set publish_time = now() - interval '1 second' where id = ?",
                draft.id());
        notices.publishDue();
        assertThat(notices.get(draft.id()).publishStatus()).isEqualTo("PUBLISHED");

        jdbc.update("update eaio_platform.notice set publish_time = now() - interval '2 seconds', "
                + "expire_time = now() - interval '1 second' where id = ?",
                draft.id());
        assertThat(notices.unread().getRecords()).extracting(NoticeDTO::id).doesNotContain(draft.id());
    }

    @Test
    @DisplayName("模板保存时校验声明，渲染时区分缺变量与未声明占位符")
    void templateValidationAndRendering() {
        String code = "it.notice." + UUID.randomUUID();
        templates.save(new NotifyTemplateSaveCmd(code, "IT 模板", "SITE", "标题 ${name}",
                "正文 ${name}", List.of("name"), "ENABLED", null, null));
        NotifyTemplateApi.RenderedTemplate rendered = templates.render(code, Map.of("name", "Alice"));
        assertThat(rendered.title()).isEqualTo("标题 Alice");
        assertThat(rendered.content()).isEqualTo("正文 Alice");
        assertThatThrownBy(() -> templates.render(code, Map.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(20042));
        assertThatThrownBy(() -> templates.save(new NotifyTemplateSaveCmd(code + ".bad", "坏模板", "SITE", null,
                "${other}", List.of("name"), "ENABLED", null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(20043));
    }
}
