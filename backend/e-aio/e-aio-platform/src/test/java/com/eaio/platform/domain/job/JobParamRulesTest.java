package com.eaio.platform.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JobParamRules} 的单测（P1 册 4.7：{@code params_json} 拒绝疑似密钥的键名）。
 */
class JobParamRulesTest {

    @Test
    @DisplayName("含 password/secret/token/key 的键名被拦（大小写与子串都拦）")
    void rejectsSecretLikeKeys() {
        assertThat(JobParamRules.firstSecretKey(Map.of("password", "x"))).contains("password");
        assertThat(JobParamRules.firstSecretKey(Map.of("dbPassword", "x"))).contains("dbPassword");
        assertThat(JobParamRules.firstSecretKey(Map.of("API_TOKEN", "x"))).contains("API_TOKEN");
        assertThat(JobParamRules.firstSecretKey(Map.of("apiKey", "x"))).contains("apiKey");
        assertThat(JobParamRules.firstSecretKey(Map.of("clientSecret", "x"))).contains("clientSecret");
        assertThat(JobParamRules.firstSecretKey(Map.of("a", "1", "tokenId", "2")))
                .as("多个键时给出首个命中的键名（报错要能定位）")
                .contains("tokenId");
    }

    @Test
    @DisplayName("普通参数不受影响；空/缺省参数不拦")
    void allowsPlainKeys() {
        assertThat(JobParamRules.firstSecretKey(Map.of("days", "90", "batchSize", "1000"))).isEmpty();
        assertThat(JobParamRules.firstSecretKey(Map.of())).isEmpty();
        assertThat(JobParamRules.firstSecretKey(null)).isEmpty();
    }

    @Test
    @DisplayName("键名为 null 不炸（直连改库可能塞进脏数据）")
    void toleratesNullKey() {
        Map<String, String> params = new HashMap<>();
        params.put(null, "v");
        assertThat(JobParamRules.firstSecretKey(params)).isEmpty();
    }
}
