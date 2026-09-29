package com.eaio.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.eaio.platform.api.CacheApi;
import com.eaio.platform.api.CacheKey;
import com.eaio.platform.api.DictApi;
import com.eaio.platform.api.ExcelApi;
import com.eaio.platform.api.FileApi;
import com.eaio.platform.api.MonitorApi;
import com.eaio.platform.api.NoticeApi;
import com.eaio.platform.api.NotifyTemplateApi;
import com.eaio.platform.api.ParamApi;
import com.eaio.platform.api.SchedulerApi;
import com.eaio.platform.api.dto.DictItemDTO;
import com.eaio.platform.api.dto.DictItemSaveCmd;
import com.eaio.platform.api.dto.DictTypeDTO;
import com.eaio.platform.api.dto.DictTypeQuery;
import com.eaio.platform.api.dto.DictTypeSaveCmd;
import com.eaio.platform.api.dto.ExcelExportCmd;
import com.eaio.platform.api.dto.ExcelImportCmd;
import com.eaio.platform.api.dto.ExcelTaskDTO;
import com.eaio.platform.api.dto.FileChunkCmd;
import com.eaio.platform.api.dto.FileChunkResult;
import com.eaio.platform.api.dto.FileDTO;
import com.eaio.platform.api.dto.FileDownloadCmd;
import com.eaio.platform.api.dto.FileMergeCmd;
import com.eaio.platform.api.dto.FileQuery;
import com.eaio.platform.api.dto.FileUploadCmd;
import com.eaio.platform.api.dto.FileUrlDTO;
import com.eaio.platform.api.dto.JobDefinition;
import com.eaio.platform.api.dto.JobRunQuery;
import com.eaio.platform.api.dto.MetricSnapshotDTO;
import com.eaio.platform.api.dto.NoticePublishCmd;
import com.eaio.platform.api.dto.NoticeQuery;
import com.eaio.platform.api.dto.NotifyTemplateDTO;
import com.eaio.platform.api.dto.NotifyTemplateQuery;
import com.eaio.platform.api.dto.NotifyTemplateSaveCmd;
import com.eaio.platform.api.dto.ParamDTO;
import com.eaio.platform.api.dto.ParamSaveCmd;
import com.eaio.platform.application.DictApiImpl;
import com.eaio.platform.application.ExcelApiImpl;
import com.eaio.platform.application.FileApiImpl;
import com.eaio.platform.application.NoticeApiImpl;
import com.eaio.platform.application.NotifyTemplateApiImpl;
import com.eaio.platform.application.ParamApiImpl;
import com.eaio.platform.application.SchedulerApiImpl;
import com.eaio.platform.application.cache.CacheApiImpl;
import com.eaio.platform.application.monitor.AlertAppService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

/** Reflection guard for the frozen P1-3 platform API surface. */
class ApiContractTest {

    private static final Map<Class<?>, List<MethodSpec>> CONTRACTS = Map.ofEntries(
            Map.entry(ParamApi.class, List.of(
                    method("get", ParamDTO.class, String.class),
                    method("getInt", int.class, String.class, int.class),
                    method("getBool", boolean.class, String.class, boolean.class),
                    method("getString", String.class, String.class, String.class),
                    method("listByGroup", List.class, String.class),
                    method("getAll", Map.class),
                    method("set", ParamDTO.class, ParamSaveCmd.class),
                    method("refresh", void.class, String.class))),
            Map.entry(DictApi.class, List.of(
                    method("getItems", List.class, String.class),
                    method("getLabel", String.class, String.class, String.class),
                    method("getPage", com.eaio.common.api.PageResult.class, DictTypeQuery.class),
                    method("add", DictTypeDTO.class, DictTypeSaveCmd.class),
                    method("up", DictTypeDTO.class, DictTypeSaveCmd.class),
                    method("del", void.class, long.class),
                    method("refresh", void.class, String.class),
                    method("addItem", DictItemDTO.class, DictItemSaveCmd.class),
                    method("upItem", DictItemDTO.class, DictItemSaveCmd.class),
                    method("delItem", void.class, long.class))),
            Map.entry(FileApi.class, List.of(
                    method("upload", FileDTO.class, FileUploadCmd.class),
                    method("uploadChunk", FileChunkResult.class, FileChunkCmd.class),
                    method("mergeChunks", FileDTO.class, FileMergeCmd.class),
                    method("download", Resource.class, FileDownloadCmd.class),
                    method("getUrl", FileUrlDTO.class, long.class, int.class),
                    method("getMeta", FileDTO.class, long.class),
                    method("del", void.class, long.class),
                    method("bind", void.class, String.class, long.class, List.class),
                    method("getPage", com.eaio.common.api.PageResult.class, FileQuery.class))),
            Map.entry(SchedulerApi.class, List.of(
                    method("register", void.class, JobDefinition.class),
                    method("trigger", long.class, String.class, Map.class),
                    method("pause", void.class, String.class),
                    method("resume", void.class, String.class),
                    method("listRuns", com.eaio.common.api.PageResult.class, JobRunQuery.class))),
            Map.entry(ExcelApi.class, List.of(
                    method("export", String.class, ExcelExportCmd.class),
                    method("importData", String.class, ExcelImportCmd.class),
                    method("getTask", ExcelTaskDTO.class, String.class),
                    method("downloadResult", Resource.class, String.class),
                    method("getErrors", List.class, String.class, int.class),
                    method("cancel", void.class, String.class))),
            Map.entry(CacheApi.class, List.of(
                    method("get", java.util.Optional.class, CacheKey.class, Class.class),
                    method("put", void.class, CacheKey.class, Object.class, int.class),
                    method("evict", void.class, CacheKey.class),
                    method("evictByPrefix", void.class, com.eaio.platform.api.CacheRegion.class, String.class))),
            Map.entry(MonitorApi.class, List.of(
                    method("metrics", MetricSnapshotDTO.class),
                    method("health", Map.class),
                    method("alerts", List.class))),
            Map.entry(NoticeApi.class, List.of(
                    method("publish", long.class, NoticePublishCmd.class),
                    method("getUnread", List.class),
                    method("markRead", void.class, long.class),
                    method("getPage", com.eaio.common.api.PageResult.class, NoticeQuery.class))),
            Map.entry(NotifyTemplateApi.class, List.of(
                    method("render", NotifyTemplateApi.RenderedTemplate.class, String.class, Map.class),
                    method("getPage", com.eaio.common.api.PageResult.class, NotifyTemplateQuery.class),
                    method("save", NotifyTemplateDTO.class, NotifyTemplateSaveCmd.class))));

    private static final Map<Class<?>, Class<?>> IMPLEMENTATIONS = Map.ofEntries(
            Map.entry(ParamApi.class, ParamApiImpl.class),
            Map.entry(DictApi.class, DictApiImpl.class),
            Map.entry(FileApi.class, FileApiImpl.class),
            Map.entry(SchedulerApi.class, SchedulerApiImpl.class),
            Map.entry(ExcelApi.class, ExcelApiImpl.class),
            Map.entry(CacheApi.class, CacheApiImpl.class),
            Map.entry(MonitorApi.class, AlertAppService.class),
            Map.entry(NoticeApi.class, NoticeApiImpl.class),
            Map.entry(NotifyTemplateApi.class, NotifyTemplateApiImpl.class));

    @Test
    @DisplayName("9 个命名接口的抽象方法与 P1-3 5.4 冻结签名一致")
    void frozenSignaturesHold() {
        for (Map.Entry<Class<?>, List<MethodSpec>> entry : CONTRACTS.entrySet()) {
            Set<MethodSpec> actual = Arrays.stream(entry.getKey().getDeclaredMethods())
                    .filter(method -> !method.isDefault())
                    .map(MethodSpec::from)
                    .collect(Collectors.toSet());
            assertThat(actual).as(entry.getKey().getSimpleName())
                    .containsExactlyInAnyOrderElementsOf(entry.getValue());
        }
    }

    @Test
    @DisplayName("每个冻结接口都有同签名的 application 实现")
    void implementationsExposeSignatures() {
        for (Map.Entry<Class<?>, Class<?>> entry : IMPLEMENTATIONS.entrySet()) {
            for (MethodSpec spec : CONTRACTS.get(entry.getKey())) {
                Method method = find(entry.getValue(), spec);
                assertThat(method).as("%s -> %s", entry.getKey().getSimpleName(), spec).isNotNull();
                assertThat(method.getReturnType()).isEqualTo(spec.returnType());
            }
        }
    }

    private static Method find(Class<?> implementation, MethodSpec spec) {
        try {
            return implementation.getMethod(spec.name(), spec.parameterTypes().toArray(Class<?>[]::new));
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static MethodSpec method(String name, Class<?> returnType, Class<?>... parameters) {
        return new MethodSpec(name, returnType, List.of(parameters));
    }

    private record MethodSpec(String name, Class<?> returnType, List<Class<?>> parameterTypes) {

        private static MethodSpec from(Method method) {
            return new MethodSpec(method.getName(), method.getReturnType(), List.of(method.getParameterTypes()));
        }
    }
}
