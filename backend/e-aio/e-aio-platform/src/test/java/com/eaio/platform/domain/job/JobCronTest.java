package com.eaio.platform.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JobCron} 的单测（P1 册 3.4.6 的 cron 方言取舍）。
 *
 * <p>重点在**拒绝**：从 RuoYi/Quartz 迁来的表达式（{@code ?}/{@code L}/{@code W}/{@code #}、5 段、7 段）
 * 必须在这里被判非法（保存时 20022），而不是"保存成功但永不触发"。
 */
class JobCronTest {

    @Test
    @DisplayName("合法：Spring 6 段表达式（含 */n、区间、列表、周名）")
    void acceptsSixFieldExpressions() {
        assertThat(JobCron.isValid("0 0 3 * * *")).as("每天 03:00（种子里 platform.job.log.clean 用的就是它）").isTrue();
        assertThat(JobCron.isValid("0 */10 * * * *")).isTrue();
        assertThat(JobCron.isValid("*/30 * * * * *")).isTrue();
        assertThat(JobCron.isValid("0 0 9-18 * * MON-FRI")).isTrue();
        assertThat(JobCron.isValid("0 15,45 8 * * *")).isTrue();
        assertThat(JobCron.isValid("  0 0 3 * * *  ")).as("两侧空白容忍").isTrue();
    }

    @Test
    @DisplayName("非法：Quartz 方言与段数不对（都必须报 20022，而不是静默永不触发）")
    void rejectsQuartzDialectAndWrongFieldCounts() {
        assertThat(JobCron.isValid("0 0 3 ? * *")).as("Quartz 的 ? Spring 不支持").isFalse();
        assertThat(JobCron.isValid("0 0 3 L * *")).as("Quartz 的 L 不支持").isFalse();
        assertThat(JobCron.isValid("0 0 3 * * 1#2")).as("Quartz 的 # 不支持").isFalse();
        assertThat(JobCron.isValid("0 0 3 * * 1W")).as("Quartz 的 W 不支持").isFalse();
        assertThat(JobCron.isValid("0 0 3 * * 1w")).as("方言字符大小写都拦").isFalse();
        assertThat(JobCron.isValid("0 3 * * *")).as("5 段（Quartz 的分 时 日 月 周）非法").isFalse();
        assertThat(JobCron.isValid("0 0 3 * * * 2026")).as("7 段（带年）非法").isFalse();
    }

    @Test
    @DisplayName("方言检测独立可测：Spring 7 的 CronExpression 其实认 ?/L/#，是本层显式拒掉（3.4.6 口径）")
    void quartzDialectIsDetectedExplicitly() {
        assertThat(org.springframework.scheduling.support.CronExpression.isValidExpression("0 0 3 ? * *"))
                .as("Spring 7 的解析器接受 ?（实测）——所以合法性不能只靠 parse 的结果")
                .isTrue();
        assertThat(JobCron.containsQuartzDialect("0 0 3 ? * *")).isTrue();
        assertThat(JobCron.containsQuartzDialect("0 0 3 * * 1#2")).isTrue();
        assertThat(JobCron.containsQuartzDialect("0 0 3 * * 1W")).isTrue();
        assertThat(JobCron.containsQuartzDialect("0 0 3 15W * *")).isTrue();
        assertThat(JobCron.containsQuartzDialect("0 0 3 L * *")).isTrue();
        assertThat(JobCron.containsQuartzDialect("0 0 3 LW * *")).isTrue();
        assertThat(JobCron.containsQuartzDialect(null)).isFalse();
    }

    @Test
    @DisplayName("方言判定的假阳性守卫：WED 含 W、JUL 含 L，但它们是完全合法的周名/月名")
    void dayAndMonthNamesAreNotTreatedAsDialect() {
        assertThat(JobCron.containsQuartzDialect("0 0 9 * * WED")).as("WED 里的 W 不是方言").isFalse();
        assertThat(JobCron.isValid("0 0 9 * * WED")).as("每周三 09:00 必须合法").isTrue();
        assertThat(JobCron.isValid("0 0 9 * * MON-FRI")).as("工作日 09:00 必须合法").isTrue();
        assertThat(JobCron.isValid("0 0 3 1 JUL *")).as("7 月 1 日 03:00 必须合法（JUL 里的 L 不是方言）").isTrue();
        assertThat(JobCron.isValid("0 0 3 1 DEC *")).as("DEC 不含方言字符").isTrue();
        assertThat(JobCron.containsQuartzDialect("0 0 3 1 JUL *")).isFalse();
    }

    @Test
    @DisplayName("非法：取值越界与空值（DDL 的 CHECK 拦不住表达式语义，只能在这里拦）")
    void rejectsOutOfRangeAndBlank() {
        assertThat(JobCron.isValid("0 0 25 * * *")).as("小时 25（3.4.8 的断言用例）").isFalse();
        assertThat(JobCron.isValid("0 0 3 32 * *")).as("日 32").isFalse();
        assertThat(JobCron.isValid("0 0 3 * 13 *")).as("月 13").isFalse();
        assertThat(JobCron.isValid(null)).isFalse();
        assertThat(JobCron.isValid("")).isFalse();
        assertThat(JobCron.isValid("   ")).isFalse();
    }

    @Test
    @DisplayName("nextRun：严格晚于给定时刻的下一个触发点；非法表达式返回空")
    void nextRunComputesNextFireTime() {
        Instant after = Instant.parse("2026-09-19T02:59:00Z");
        // 每天 03:00：下一个点是当天 03:00（用默认时区解释，测试只断言"存在且在 24 小时内"）
        assertThat(JobCron.nextRun("0 0 3 * * *", after))
                .isPresent()
                .get()
                .satisfies(next -> assertThat(next).isAfter(after));
        assertThat(JobCron.nextRun("*/30 * * * * *", after))
                .isPresent()
                .get()
                .satisfies(next -> assertThat(next).isBefore(after.plusSeconds(31)));
        assertThat(JobCron.nextRun("0 0 25 * * *", after)).as("非法表达式没有「下一次」").isEmpty();
    }
}
