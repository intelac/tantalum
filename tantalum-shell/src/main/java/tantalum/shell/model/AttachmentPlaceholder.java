package tantalum.shell.model;

import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;

/** 附件只发文字时的样子：文件给说明和只读路径，图片给“不支持图片”的占位（需求 A6、A7）。 */
final class AttachmentPlaceholder {

    private AttachmentPlaceholder() {}

    static String text(AttachmentRef ref) {
        return switch (ref) {
            case ImageRef image -> AttachmentText.textOnly(image);
            case FileRef file -> AttachmentText.file(file);
        };
    }
}
