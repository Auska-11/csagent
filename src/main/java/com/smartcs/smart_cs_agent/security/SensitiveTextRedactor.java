package com.smartcs.smart_cs_agent.security;

import java.util.regex.Pattern;

/**
 * 写入 Memory 和摘要前使用的确定性文本脱敏工具。
 *
 * <p>这里只处理高风险、格式比较稳定的内容：凭据、邮箱、手机号、身份证和长数字。姓名、地址等
 * 需要结合业务上下文判断，不能用一个宽泛正则盲目删除。</p>
 */
public final class SensitiveTextRedactor {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(?:(?<![\\w-])(?:api[ _-]?key|access[ _-]?token|token|password|secret)"
                    + "(?![\\w-])|(?:密码|口令|密钥|令牌))"
                    + "\\s*(?:[:=：]|是)\\s*[^\\s,，;；]+"
    );
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![\\w.+-])[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}(?![\\w.-])"
    );
    private static final Pattern CHINESE_ID = Pattern.compile(
            "(?<![0-9A-Za-z])\\d{17}[0-9Xx](?![0-9A-Za-z])"
    );
    private static final Pattern MOBILE_PHONE = Pattern.compile(
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)"
    );
    private static final Pattern LONG_NUMBER = Pattern.compile(
            "(?<!\\d)\\d{12,19}(?!\\d)"
    );
    private static final Pattern REDACTION_MARKER = Pattern.compile("\\[REDACTED]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_LABEL = Pattern.compile(
            "(?i)(?:手机号码?|联系电话|联系方式|电话|电子邮箱|邮箱|身份证号?|银行卡号?|"
                    + "\\b(?:email|phone|id\\s*card|api[_-]?key|access[_-]?token|token|password|secret)\\b)"
    );
    private static final Pattern SENSITIVE_ONLY_FILLER = Pattern.compile(
            "(?i)(?:我的?\\s*手机\\s*(?:号码)?\\s*(?:是|为)?"
                    + "|我的?\\s*(?:偏好\\s*(?:是|为)?|(?:只)?喜欢)"
                    + "|请\\s*(?:帮我\\s*)?记住"
                    + "|我的?|本人|这个|该|请|联系|告诉|提供|填写|使用|用|可以|是|为|叫|如下"
                    + "|号码|账号|信息"
                    + "|\\b(?:my|the|is|number|contact|me|at|please|remember|like)\\b)"
    );
    private static final Pattern LETTER_OR_NUMBER = Pattern.compile("[\\p{L}\\p{N}]");

    private SensitiveTextRedactor() {
    }

    /** 替换高风险片段，但保留其余上下文。 */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String redacted = CREDENTIAL.matcher(text).replaceAll(REDACTED);
        redacted = EMAIL.matcher(redacted).replaceAll(REDACTED);
        redacted = CHINESE_ID.matcher(redacted).replaceAll(REDACTED);
        redacted = MOBILE_PHONE.matcher(redacted).replaceAll(REDACTED);
        return LONG_NUMBER.matcher(redacted).replaceAll(REDACTED);
    }

    /** 长期偏好使用单行格式，并按 Unicode 字符数截断。 */
    public static String sanitizeSingleLine(String text, int maxCodePoints) {
        return keepHead(normalizeSingleLine(text), maxCodePoints);
    }

    /** 先完整脱敏并压缩空白，调用方可以在截断前记录是否真的发生过脱敏。 */
    public static String normalizeSingleLine(String text) {
        return redact(text).replaceAll("\\s+", " ").trim();
    }

    /** 对已经规范化的文本限制头部长度，不会从中间切开 [REDACTED]。 */
    public static String limitSingleLine(String normalizedText, int maxCodePoints) {
        return keepHead(normalizedText == null ? "" : normalizedText, maxCodePoints);
    }

    public static boolean containsRedactionMarker(String text) {
        return text != null && REDACTION_MARKER.matcher(text).find();
    }

    /** 摘要优先保留最近内容，因此超长时从前面淘汰。 */
    public static String redactAndKeepTail(String text, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            throw new IllegalArgumentException("maxCodePoints must be greater than 0");
        }
        String redacted = redact(text);
        int codePointCount = redacted.codePointCount(0, redacted.length());
        if (codePointCount <= maxCodePoints) {
            return redacted;
        }
        if (maxCodePoints <= 3) {
            int start = redacted.offsetByCodePoints(0, codePointCount - maxCodePoints);
            return redacted.substring(start);
        }
        int retainedCodePoints = maxCodePoints - 3;
        int start = redacted.offsetByCodePoints(0, codePointCount - retainedCodePoints);
        return "..." + redacted.substring(start);
    }

    /** 只有脱敏占位符、空白和标点时，不值得作为商品偏好保存。 */
    public static boolean hasMeaningfulContent(String sanitizedText) {
        return hasMeaningfulContent(sanitizedText, containsRedactionMarker(sanitizedText));
    }

    /**
     * sensitiveContentWasRedacted 来自截断前的完整文本。
     * 这样即使长度上限把占位符整体移除，也仍会按“敏感输入”规则判断剩余内容。
     */
    public static boolean hasMeaningfulContent(
            String sanitizedText,
            boolean sensitiveContentWasRedacted) {
        if (sanitizedText == null || sanitizedText.isBlank()) {
            return false;
        }

        // 没有发生脱敏时，只要仍有字母或数字，就可以作为普通商品偏好保存。
        // 只有出现脱敏占位符时，才继续去掉“我的手机号是”这类说明词，判断是否还剩商品内容。
        if (!sensitiveContentWasRedacted) {
            return LETTER_OR_NUMBER.matcher(sanitizedText).find();
        }

        String withoutMarkers = REDACTION_MARKER.matcher(sanitizedText).replaceAll("");
        String withoutSensitiveLabels = SENSITIVE_LABEL.matcher(withoutMarkers).replaceAll("");
        String withoutFillers = SENSITIVE_ONLY_FILLER.matcher(withoutSensitiveLabels).replaceAll("");
        return LETTER_OR_NUMBER.matcher(withoutFillers).find();
    }

    private static String keepHead(String text, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            throw new IllegalArgumentException("maxCodePoints must be greater than 0");
        }
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxCodePoints);
        int markerStart = text.lastIndexOf(REDACTED, Math.max(0, end - 1));
        if (markerStart >= 0 && markerStart < end && markerStart + REDACTED.length() > end) {
            // 半个占位符既难读，也会让后续有效内容判断误把 RED 当作普通英文。
            return text.substring(0, markerStart).stripTrailing();
        }
        return text.substring(0, end);
    }
}
