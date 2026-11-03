package tantalum.kernel.attachment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.ImageRef;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolResultBlock;

/**
 * 一次请求里图片的预算（需求 A10，同 DSH {@code 2026-09-10-image-offload-events.md}）：从最新的消息往前累计图片的 base64 大小，
 * 超过 20 MiB 后更早的图片全部省略，换成说明文字，模型要看可以用 {@code read_file} 读只读路径。已经省略过的图片不管新的累计结果
 * 如何都继续省略：前缀保持稳定，prompt 缓存不会反复失效。
 */
public final class ImageBudget {

    /** 一次请求里图片的 base64 合计上限。 */
    public static final long MAX_BASE64_BYTES = 20L * 1024 * 1024;

    /**
     * 省略后的消息。{@code omitted} 是这次请求里省略的全部图片 ID（按出现顺序），{@code newlyOmitted} 是其中第一次省略的。
     */
    public record Applied(List<Message> messages, List<String> omitted, List<String> newlyOmitted) {}

    private ImageBudget() {}

    /** 按预算省略。{@code alreadyOmitted} 是会话里省略过的图片。 */
    public static Applied apply(List<Message> messages, List<String> alreadyOmitted) {
        Set<String> already = Set.copyOf(alreadyOmitted);
        Set<String> omit = new LinkedHashSet<>();
        long total = 0;
        boolean over = false;
        for (int m = messages.size() - 1; m >= 0; m--) {
            List<ImageRef> images = images(messages.get(m));
            for (int i = images.size() - 1; i >= 0; i--) {
                ImageRef image = images.get(i);
                if (already.contains(image.id()) || omit.contains(image.id())) {
                    continue;
                }
                long size = base64(image.bytes());
                if (over || total + size > MAX_BASE64_BYTES) {
                    over = true;
                    omit.add(image.id());
                } else {
                    total += size;
                }
            }
        }
        Set<String> all = new LinkedHashSet<>(alreadyOmitted);
        all.addAll(omit);
        List<Message> kept = omit(messages, all);
        List<String> here = idsIn(messages, all);
        return new Applied(kept, here, here.stream().filter(id -> !already.contains(id)).toList());
    }

    /** 把这些图片换成省略说明。估算上下文大小时用会话里省略过的图片调它，和实际发送的一致。 */
    public static List<Message> omit(List<Message> messages, Iterable<String> omittedIds) {
        Set<String> omitted = new LinkedHashSet<>();
        omittedIds.forEach(omitted::add);
        if (omitted.isEmpty()) {
            return messages;
        }
        List<Message> out = new ArrayList<>(messages.size());
        for (Message message : messages) {
            List<ContentBlock> content = new ArrayList<>(message.content().size());
            boolean changed = false;
            for (ContentBlock block : message.content()) {
                ContentBlock replaced = replace(block, omitted);
                changed |= replaced != block;
                content.add(replaced);
            }
            out.add(changed ? new Message(message.role(), content, message.interrupted()) : message);
        }
        return List.copyOf(out);
    }

    private static ContentBlock replace(ContentBlock block, Set<String> omitted) {
        if (block instanceof AttachmentBlock attachment && attachment.ref() instanceof ImageRef image && omitted.contains(image.id())) {
            return new TextBlock(AttachmentText.omitted(image));
        }
        if (block instanceof ToolResultBlock result && result.images().stream().anyMatch(image -> omitted.contains(image.id()))) {
            StringBuilder text = new StringBuilder(result.text());
            List<ImageRef> kept = new ArrayList<>();
            for (ImageRef image : result.images()) {
                if (omitted.contains(image.id())) {
                    text.append('\n').append(AttachmentText.omitted(image));
                } else {
                    kept.add(image);
                }
            }
            return new ToolResultBlock(result.toolCallId(), text.toString(), result.error(), result.code(), kept);
        }
        return block;
    }

    /** 一条消息里的图片，按出现顺序：用户附件和工具结果里的图片。 */
    private static List<ImageRef> images(Message message) {
        List<ImageRef> images = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block instanceof AttachmentBlock attachment && attachment.ref() instanceof ImageRef image) {
                images.add(image);
            } else if (block instanceof ToolResultBlock result) {
                images.addAll(result.images());
            }
        }
        return images;
    }

    private static List<String> idsIn(List<Message> messages, Set<String> ids) {
        Set<String> found = new LinkedHashSet<>();
        for (Message message : messages) {
            for (ImageRef image : images(message)) {
                if (ids.contains(image.id())) {
                    found.add(image.id());
                }
            }
        }
        return List.copyOf(found);
    }

    /** base64 后的字节数。请求版本不会比存储的大，按存储的算。 */
    static long base64(long bytes) {
        return 4 * ((bytes + 2) / 3);
    }
}
