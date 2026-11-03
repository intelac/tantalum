package tantalum.value.attachment;

/**
 * 附件在发给模型的消息里对应的固定英文文字（同 DSH {@code packages/llm/llm/src/content.ts}）。
 * 内核估算和客户端发送都用这里，两边算的是同一段文字。值模块只放 record、接口和枚举，所以写成只有静态方法的接口。
 */
public interface AttachmentText {

    /** 图片前面的一行说明（DSH {@code requestImageHandleText}）。 */
    static String image(ImageRef ref) {
        return "Image " + quoted(ref.name()) + " (sha256:" + digest(ref) + ", " + ref.width() + "x" + ref.height()
                + "px). Read-only copy: " + quoted(ref.readonlyPath()) + ".";
    }

    /** 文件不发内容，只给说明和只读路径，模型要看时自己用 read_file 读（DSH {@code fileHandleText}）。 */
    static String file(FileRef ref) {
        return "[File " + quoted(ref.name()) + " (" + ref.bytes() + " bytes, sha256:" + digest(ref)
                + "): verbatim read-only copy saved at " + quoted(ref.readonlyPath())
                + ". Read that path with read_file when its contents are needed.]";
    }

    /** 超出请求的图片预算而省略的图片（DSH {@code offloadedImageText}）。 */
    static String omitted(ImageRef ref) {
        return "[image omitted to fit request image limits; " + quoted(ref.name()) + " (sha256:" + digest(ref)
                + "). Read-only copy: " + quoted(ref.readonlyPath()) + ".]";
    }

    /** 模型不收图片时的占位（DSH {@code textOnlyImageText}）。 */
    static String textOnly(ImageRef ref) {
        return "[image omitted because this model accepts text only; sha256:" + digest(ref) + "]";
    }

    private static String digest(AttachmentRef ref) {
        return ref.hash().substring(0, 8);
    }

    /** 名字上传时已清洗，不会有引号和反斜杠；这里仍按 JSON 转义，防止拼出歧义的文字。 */
    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
