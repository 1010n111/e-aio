package com.eaio.platform.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Delete;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 物理清理不能误用 {@code @TableLogic} 的回归断言。 */
class FileMapperContractTest {

    @Test
    @DisplayName("过期文件使用 DELETE，而不是 BaseMapper 的逻辑删除 UPDATE")
    void purgeByIdIsPhysicalDelete() throws NoSuchMethodException {
        Delete delete = FileMapper.class.getMethod("purgeById", long.class).getAnnotation(Delete.class);

        assertThat(delete).isNotNull();
        assertThat(delete.value()[0]).containsIgnoringCase("delete from eaio_platform.file")
                .containsIgnoringCase("deleted = true");
    }
}
