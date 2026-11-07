package tantalum.shell.attachment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;

/**
 * 工具结果里的图片（设计“{@code ToolImages}”）：本机工作区读到的图片、MCP 工具返回的图片、浏览器截图，都照附件规则
 * 嗅探类型、缩小、去重、算配额，存进本人的附件目录，交给模型的是 {@link ImageRef}，会话文件里也只有引用。
 * 模型不收图片时由各条模型路线换成一行文字，这里总是存（换模型以后图片还在，附件里也看得到）。
 */
public final class ToolImages {

    /** 工具送回的一张图片：声称的类型和原始字节。类型只是提示，存的时候按内容嗅探。 */
    public record Image(String mediaType, byte[] bytes) {}

    private final AttachmentStore store;

    public ToolImages(AttachmentStore store) {
        this.store = store;
    }

    /** 一个结果最多收几张图片，同“一条消息最多几张”（{@code tantalum.attachments.max-images-per-message}）。 */
    public int maxImages() {
        return store.limits().maxImagesPerMessage();
    }

    /**
     * 存好每张图，文字后面逐行加 {@link AttachmentText#image} 那一行。{@code name} 是附件的名字，几张图同名也没关系：
     * 名字属于每一次出现，存储只看内容。存不下（配额满、太大、不是图片）时整个结果是错误，写明原因。
     */
    public ToolOutput output(Principal user, String text, boolean error, List<Image> images, String name) throws IOException {
        if (images.isEmpty()) {
            return new ToolOutput(text, error);
        }
        List<ImageRef> stored = new ArrayList<>();
        for (Image image : images) {
            try {
                AttachmentRef ref = store.upload(user, name, image.mediaType(), new ByteArrayInputStream(image.bytes()),
                        image.bytes().length);
                if (!(ref instanceof ImageRef picture)) {
                    return ToolOutput.error("Not an image: " + name);
                }
                stored.add(picture);
            } catch (AttachmentRejected rejected) {
                return ToolOutput.error("Cannot store image " + name + ": " + rejected.getMessage());
            }
        }
        List<String> lines = new ArrayList<>();
        if (!text.isEmpty()) {
            lines.add(text);
        }
        stored.forEach(ref -> lines.add(AttachmentText.image(ref)));
        return new ToolOutput(String.join("\n", lines), error, stored);
    }
}
