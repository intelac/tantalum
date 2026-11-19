package tantalum.shell.handler;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;
import static tantalum.shell.handler.ModelHandler.*;
import tantalum.shell.handler.ModelHandler.*;
import tantalum.shell.model.AttachmentMedia;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.route.Routes;
import tantalum.shell.route.UsageWeights;
import tantalum.value.id.RunRef;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class ModelHandlerFixture {

    private ModelHandlerFixture() {}

    public static ModelHandler of(Routes routes, Function<RunRef, ChunkListener> listeners, Clock clock, Function<RunRef, AttachmentMedia> media) {
        return new ModelHandler(routes, listeners, clock, media, run -> true, UsageWeights.NONE);
    }

    public static ModelHandler of(ModelClient client, CallLimiter limiter, Duration waitTimeout, Function<RunRef, ChunkListener> listeners, Clock clock) {
        return new ModelHandler(client, limiter, waitTimeout, listeners, clock, run -> AttachmentMedia.NONE);
    }
}
