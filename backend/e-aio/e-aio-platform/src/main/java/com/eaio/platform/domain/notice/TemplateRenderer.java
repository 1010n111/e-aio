package com.eaio.platform.domain.notice;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eaio.common.exception.BusinessException;
import com.eaio.platform.api.PlatformErrorCode;
import org.springframework.stereotype.Component;

/** 纯文本 ${var} 渲染器：只做占位符替换，不执行表达式。 */
@Component
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_.-]*)}");

    public Rendered render(String title, String content, Collection<String> variables, Map<String, String> params) {
        Set<String> declared = normalized(variables);
        Set<String> used = placeholders(title, content);
        for (String name : used) {
            if (!declared.contains(name)) {
                throw new BusinessException(PlatformErrorCode.NOTIFY_RENDER_FAILED,
                        "模板包含未声明占位符：${" + name + "}");
            }
        }
        Map<String, String> actual = params == null ? Map.of() : params;
        for (String name : used) {
            if (!actual.containsKey(name) || actual.get(name) == null) {
                throw new BusinessException(PlatformErrorCode.NOTIFY_VARIABLE_MISSING,
                        "模板变量缺失：" + name);
            }
        }
        return new Rendered(replace(title, actual), replace(content, actual));
    }

    /** 保存模板时只校验声明覆盖，不要求运行时参数。 */
    public void validateDeclarations(String title, String content, Collection<String> variables) {
        Set<String> declared = normalized(variables);
        for (String name : placeholders(title, content)) {
            if (!declared.contains(name)) {
                throw new BusinessException(PlatformErrorCode.NOTIFY_RENDER_FAILED,
                        "模板包含未声明占位符：${" + name + "}");
            }
        }
    }

    private static Set<String> placeholders(String... texts) {
        Set<String> names = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private static Set<String> normalized(Collection<String> variables) {
        Set<String> names = new LinkedHashSet<>();
        if (variables != null) {
            for (String variable : variables) {
                if (variable != null && !variable.isBlank()) {
                    names.add(variable.trim());
                }
            }
        }
        return names;
    }

    private static String replace(String text, Map<String, String> params) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(params.get(matcher.group(1))));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public record Rendered(String title, String content) {
    }
}
