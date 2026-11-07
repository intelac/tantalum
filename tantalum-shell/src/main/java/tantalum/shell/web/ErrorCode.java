package tantalum.shell.web;

import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.attachment.AttachmentRejected;
import tantalum.shell.memory.MemoryStore;
import tantalum.shell.route.ChoiceCheck;

/**
 * 接口错误的代码（console-i18n I9，照 DSH 的 {@code <领域>/<原因>}）：控制台按代码从字典里取文字，参数里的数字和名字由它按语言格式化；
 * {@code error} 字段是一句英文诊断，给开发者和日志看。清单和 {@code console/src/api/errorCodes.json} 相同（ErrorCodeTest 比对）。
 * 默认状态码是这个错误平常回的；同一个代码状态码会变的（会话不可用、提交被拒），调用方另给。
 */
public enum ErrorCode {
    /** 只有写错的客户端才会碰到的（JSON 写错、缺字段），诊断照旧写具体原因。 */
    REQUEST_INVALID("request/invalid", 400),
    SERVER_BUSY("server/busy", 504),
    SERVER_SAVE_FAILED("server/save-failed", 500),
    SESSION_NOT_FOUND("session/not-found", 404),
    SESSION_UNAVAILABLE("session/unavailable", 503),
    SESSION_UNREADABLE("session/unreadable", 500),
    SESSION_BUSY("session/busy", 409),
    SUBMISSION_REJECTED("submission/rejected", 409),
    RUN_NOT_FOUND("run/not-found", 404),
    RUN_CANCEL_REJECTED("run/cancel-rejected", 409),
    TRACE_NOT_FOUND("trace/not-found", 404),
    ATTACHMENT_NOT_FOUND("attachment/not-found", 404),
    ATTACHMENT_READ_FAILED("attachment/read-failed", 500),
    ATTACHMENT_NOT_STORED("attachment/not-stored", 500),
    /** 参数 {@code limit}：字节数。 */
    ATTACHMENT_TOO_LARGE("attachment/too-large", 413),
    /** 参数 {@code limit}：字节数。 */
    ATTACHMENT_IMAGE_TOO_LARGE("attachment/image-too-large", 413),
    /** 参数 {@code width}、{@code height}、{@code limit}：边长上限，像素。 */
    ATTACHMENT_IMAGE_EDGE("attachment/image-edge", 422),
    /** 参数 {@code width}、{@code height}、{@code limit}：像素总数上限。 */
    ATTACHMENT_IMAGE_PIXELS("attachment/image-pixels", 422),
    ATTACHMENT_INVALID_IMAGE("attachment/invalid-image", 422),
    ATTACHMENT_QUOTA_FULL("attachment/quota-full", 413),
    ATTACHMENT_BUSY("attachment/busy", 503),
    /** 参数 {@code max}、{@code count}。 */
    ATTACHMENT_TOO_MANY_IMAGES("attachment/too-many-images", 400),
    /** 参数 {@code limit}：一条消息里图片合计的字节数。 */
    ATTACHMENT_IMAGES_TOO_LARGE("attachment/images-too-large", 400),
    /** 参数 {@code max}、{@code count}。 */
    ATTACHMENT_TOO_MANY_FILES("attachment/too-many-files", 400),
    ATTACHMENT_MODEL_NO_IMAGES("attachment/model-no-images", 400),
    /** 参数 {@code route}。 */
    MODEL_ROUTE_UNAVAILABLE("model/route-unavailable", 400),
    /** 参数 {@code model}：模型 ID。 */
    MODEL_NOT_OFFERED("model/not-offered", 400),
    /** 参数 {@code model}：显示名。 */
    MODEL_NO_LEVELS("model/no-levels", 400),
    /** 参数 {@code model}、{@code levels}：可选的档位 ID。 */
    MODEL_LEVEL_REQUIRED("model/level-required", 400),
    /** 参数 {@code model}、{@code level}、{@code levels}。 */
    MODEL_LEVEL_UNKNOWN("model/level-unknown", 400),
    /** 参数 {@code model}。 */
    MODEL_TOOL_CHANGES("model/tool-changes", 400),
    QUESTION_GONE("question/gone", 404),
    /** 参数 {@code name}。 */
    PLUGIN_NOT_FOUND("plugin/not-found", 404),
    /** 参数 {@code name}。 */
    PLUGIN_REQUIRED("plugin/required", 400),
    MEMORY_NOT_FOUND("memory/not-found", 404),
    MEMORY_INVALID_NAME("memory/invalid-name", 400),
    /** Windows 的设备名（con、nul、com1 这类），所有平台都不收（windows-server N5）。 */
    MEMORY_RESERVED_NAME("memory/reserved-name", 400),
    /** 参数 {@code field}：type、description 或 content。 */
    MEMORY_INVALID("memory/invalid", 400),
    /** 参数 {@code limit}：一条记忆正文的字节数上限。 */
    MEMORY_TOO_LONG("memory/too-long", 400),
    /** 参数 {@code max}：条数上限。 */
    MEMORY_FULL("memory/full", 400),
    /** 参数 {@code limit}：索引的字节数上限。 */
    MEMORY_INDEX_FULL("memory/index-full", 400),
    MEMORY_CREDENTIAL("memory/credential", 400),
    SETTINGS_INVALID("settings/invalid", 400),
    AUTH_INVALID_CREDENTIALS("auth/invalid-credentials", 401),
    AUTH_TOO_MANY_ATTEMPTS("auth/too-many-attempts", 429),
    AUTH_NOT_AUTHENTICATED("auth/not-authenticated", 401),
    /** 登录了但不是管理员（ops-hardening-2 需求 2.4）。 */
    AUTH_FORBIDDEN("auth/forbidden", 403),
    USER_BAD_NAME("user/bad-name", 400),
    USER_RESERVED_NAME("user/reserved-name", 400),
    USER_EXISTS("user/exists", 409),
    USER_NOT_FOUND("user/not-found", 404),
    /** 管理员不能停用或取消自己（ops-hardening-2 D2）。 */
    USER_SELF("user/self", 400),
    /** 系统至少保留一个启用的管理员。 */
    USER_LAST_ADMIN("user/last-admin", 409);

    private final String wire;
    private final int status;

    ErrorCode(String wire, int status) {
        this.wire = wire;
        this.status = status;
    }

    public static ErrorCode of(AttachmentRejected.Reason reason) {
        return switch (reason) {
            case TOO_LARGE -> ATTACHMENT_TOO_LARGE;
            case IMAGE_FILE_TOO_LARGE -> ATTACHMENT_IMAGE_TOO_LARGE;
            case IMAGE_TOO_LARGE -> ATTACHMENT_IMAGE_EDGE;
            case IMAGE_TOO_MANY_PIXELS -> ATTACHMENT_IMAGE_PIXELS;
            case QUOTA_FULL -> ATTACHMENT_QUOTA_FULL;
            case INVALID_IMAGE -> ATTACHMENT_INVALID_IMAGE;
            case BUSY -> ATTACHMENT_BUSY;
        };
    }

    public static ErrorCode of(AttachmentLimits.Violation.Kind kind) {
        return switch (kind) {
            case TOO_MANY_IMAGES -> ATTACHMENT_TOO_MANY_IMAGES;
            case IMAGES_TOO_LARGE -> ATTACHMENT_IMAGES_TOO_LARGE;
            case TOO_MANY_FILES -> ATTACHMENT_TOO_MANY_FILES;
        };
    }

    public static ErrorCode of(ChoiceCheck.Problem.Kind kind) {
        return switch (kind) {
            case ROUTE_UNAVAILABLE -> MODEL_ROUTE_UNAVAILABLE;
            case MODEL_NOT_OFFERED -> MODEL_NOT_OFFERED;
            case NO_LEVELS -> MODEL_NO_LEVELS;
            case LEVEL_REQUIRED -> MODEL_LEVEL_REQUIRED;
            case LEVEL_UNKNOWN -> MODEL_LEVEL_UNKNOWN;
            case TOOL_CHANGES -> MODEL_TOOL_CHANGES;
            case NO_IMAGES -> ATTACHMENT_MODEL_NO_IMAGES;
        };
    }

    public static ErrorCode of(MemoryStore.Problem problem) {
        return switch (problem) {
            case INVALID_NAME -> MEMORY_INVALID_NAME;
            case RESERVED_NAME -> MEMORY_RESERVED_NAME;
            case INVALID -> MEMORY_INVALID;
            case TOO_LONG -> MEMORY_TOO_LONG;
            case FULL -> MEMORY_FULL;
            case INDEX_FULL -> MEMORY_INDEX_FULL;
            case CREDENTIAL -> MEMORY_CREDENTIAL;
        };
    }

    /** 接口里的写法，如 {@code attachment/too-large}。 */
    public String wire() {
        return wire;
    }

    public int status() {
        return status;
    }
}
