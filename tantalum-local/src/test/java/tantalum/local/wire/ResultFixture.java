package tantalum.local.wire;

import static tantalum.local.wire.Result.*;
import tantalum.local.wire.Result.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class ResultFixture {

    private ResultFixture() {}

    public static Result.Image image(String mime, String base64) {
        return new Result.Image(mime, base64, null);
    }
}
