package tantalum.value.attachment;

import static tantalum.value.attachment.ImageRef.*;
import tantalum.value.attachment.ImageRef.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class ImageRefFixture {

    private ImageRefFixture() {}

    public static ImageRef of(String id, String name, String mediaType, long bytes, int width, int height) {
        return new ImageRef(id, name, mediaType, bytes, width, height, null, null);
    }
}
