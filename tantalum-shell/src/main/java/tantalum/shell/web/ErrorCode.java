package tantalum.shell.web;

import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.attachment.AttachmentRejected;
import tantalum.shell.route.ChoiceCheck;

/**
 * 接口错误的代码（照 DSH 的 {@code <领域>/<原因>}）：控制台按代码从字典里取文字，参数里的数字和名字由它按语言格式化；
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
    /** 已归档的会话不能再提问。 */
    SESSION_ARCHIVED("session/archived", 409),
    /** 运行中、等你回答的会话不能归档。 */
    SESSION_ARCHIVE_BUSY("session/archive-busy", 409),
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
    /** 排队的那一条已经开始运行、被移除或改过了。 */
    QUEUE_GONE("queue/gone", 404),
    /** 带附件的排队提问不能改文字。 */
    QUEUE_ATTACHMENTS("queue/attachments", 409),
    /** 带点名技能的排队提问不能改文字：改了文字，附上的技能内容就对不上了。 */
    QUEUE_SKILLS("queue/skills", 409),
    /** 技能目录、wiki 目录的写法不对：要绝对路径或 {@code ~/} 开头，最长 1000 个字符，没有控制字符。 */
    LOCAL_DIR_INVALID("local/dir-invalid", 400),
    PROJECT_NOT_FOUND("project/not-found", 404),
    /** 不是绝对路径。 */
    PROJECT_PATH_INVALID("project/path-invalid", 400),
    /** 电脑上不存在，或不在连接程序允许的目录里。 */
    PROJECT_PATH_NOT_ALLOWED("project/path-not-allowed", 400),
    PROJECT_PATH_TAKEN("project/path-taken", 409),
    /** 参数 {@code max}：最长字数。 */
    PROJECT_TITLE_INVALID("project/title-invalid", 400),
    PROJECT_TITLE_TAKEN("project/title-taken", 409),
    LOCAL_NOT_CONNECTED("local/not-connected", 409),
    /** 连接程序是协议 2，不会列子目录。 */
    LOCAL_PROTOCOL_OLD("local/protocol-old", 409),
    /** 电脑没按时回答。 */
    LOCAL_NO_ANSWER("local/no-answer", 504),
    /** 服务器上没有放连接程序的 jar（connector-jar 需求 2.1）。 */
    LOCAL_CONNECTOR_MISSING("local/connector-missing", 404),
    /** 参数 {@code name}。 */
    PLUGIN_NOT_FOUND("plugin/not-found", 404),
    /** 开关锁着。参数 {@code name}、{@code source}（{@code code}、{@code config}、{@code admin}），管理员写了原因时还有 {@code reason}。 */
    PLUGIN_LOCKED("plugin/locked", 403),
    /** 参数的值不合格。参数 {@code reason}（{@code type}、{@code range}、{@code tooLong}、{@code tooMany}、{@code unknown}）和 {@code key}。 */
    PARAM_INVALID("param/invalid", 400),
    /** 只有管理员能改的参数。参数 {@code key}。 */
    PARAM_FORBIDDEN("param/forbidden", 403),
    // 下面几个由记忆插件的接口发出（tantalum.memory.api.MemoryEndpoints 写同样的字面值；ErrorCodeTest 比对），控制台按它们显示文字
    MEMORY_NOT_FOUND("memory/not-found", 404),
    MEMORY_INVALID_NAME("memory/invalid-name", 400),
    /** Windows 的设备名（con、nul、com1 这类），所有平台都不收。 */
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
    /** 登录了但不是管理员。 */
    AUTH_FORBIDDEN("auth/forbidden", 403),
    /** 写请求来自别的站。 */
    AUTH_BAD_ORIGIN("auth/bad-origin", 403),
    /** 新密码不到 {@code UserStore.MIN_PASSWORD} 位。 */
    AUTH_PASSWORD_TOO_SHORT("auth/password-too-short", 400),
    /** 带了 {@code viewAs} 但不是管理员，或者部署的人关掉了只读查看。 */
    VIEW_FORBIDDEN("view/forbidden", 403),
    /** 只读查看时发了写请求，或者读的不是会话、轨迹这些允许看的接口。 */
    VIEW_READ_ONLY("view/read-only", 403),
    USER_BAD_NAME("user/bad-name", 400),
    USER_RESERVED_NAME("user/reserved-name", 400),
    USER_EXISTS("user/exists", 409),
    USER_NOT_FOUND("user/not-found", 404),
    /** 管理员不能停用或取消自己。 */
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

    /** 接口里的写法，如 {@code attachment/too-large}。 */
    public String wire() {
        return wire;
    }

    public int status() {
        return status;
    }
}
