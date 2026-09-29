package com.eaio.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Three-way permission contract guard for the P1-3 table, backend, and frontend. */
class PermissionCodeContractTest {

    private static final String SPEC_FILE =
            "docs/04-企业级一体化管理系统-e-aio-详细设计说明书-P1-3-platform.md";
    private static final Pattern CODE = Pattern.compile("platform:[A-Za-z][A-Za-z0-9]*:[A-Za-z][A-Za-z0-9]*");
    private static final Pattern AUTHORITY = Pattern.compile("hasAuthority\\('([^']+)'\\)");

    @Test
    @DisplayName("7.3 权限点总表、后端 @PreAuthorize 与前端调用串逐字一致")
    void permissionSetsMatch() {
        Path root = repoRoot();
        Set<String> table = tableCodes(root.resolve(SPEC_FILE));
        Set<String> backend = sourceCodes(root.resolve("backend/e-aio/e-aio-platform/src/main/java"), AUTHORITY);
        Set<String> frontend = sourceCodes(root.resolve("frontend/src"), CODE);

        assertThat(table).as("7.3 权限点表不得为空").isNotEmpty();
        assertThat(backend).as("后端注解必须覆盖且只覆盖 7.3 权限点")
                .containsExactlyInAnyOrderElementsOf(table);
        assertThat(frontend).as("前端调用串必须覆盖且只覆盖 7.3 权限点")
                .containsExactlyInAnyOrderElementsOf(table);
    }

    private static Set<String> tableCodes(Path spec) {
        String content = read(spec);
        int start = content.indexOf("### 7.3 权限点总表");
        int end = content.indexOf("### 7.4", start);
        if (start < 0 || end < 0) {
            throw new IllegalStateException("找不到 P1-3 7.3 权限点表");
        }
        return find(CODE, content.substring(start, end));
    }

    private static Set<String> sourceCodes(Path directory, Pattern pattern) {
        Set<String> codes = new HashSet<>();
        try (Stream<Path> files = Files.walk(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(PermissionCodeContractTest::isSource)
                    .forEach(path -> codes.addAll(find(pattern, read(path))));
        } catch (IOException e) {
            throw new IllegalStateException("读取权限点源码失败：" + directory, e);
        }
        return codes;
    }

    private static boolean isSource(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".js") || name.endsWith(".vue")
                || name.endsWith(".ts") || name.endsWith(".tsx");
    }

    private static Set<String> find(Pattern pattern, String content) {
        Set<String> values = new HashSet<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            values.add(matcher.groupCount() == 0 ? matcher.group() : matcher.group(1));
        }
        return values;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败：" + path, e);
        }
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(SPEC_FILE)) && Files.isDirectory(current.resolve("frontend/src"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 e-aio 仓库根目录");
    }
}
