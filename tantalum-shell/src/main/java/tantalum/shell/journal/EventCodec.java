package tantalum.shell.journal;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.json.JsonText;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.event.Cancel;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.TimerFired;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.WorkDone;
import tantalum.value.json.Json;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.OwnerRef;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/** 事件与 JSON 互转。按类型名和版本查注册表，未注册的一律报错，不跳过。 */
public final class EventCodec {

    private record Key(String type, int version) {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
        @JsonSubTypes.Type(value = ReasoningBlock.class, name = "reasoning"),
        @JsonSubTypes.Type(value = ToolCallBlock.class, name = "toolCall"),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = "toolResult"),
        @JsonSubTypes.Type(value = AttachmentBlock.class, name = "attachment"),
        @JsonSubTypes.Type(value = ToolChangeBlock.class, name = "toolChange")
    })
    private interface ContentBlockMixin {}

    /** 投影才有 deferred 的工具，journal 里的都不是：false 不写出，和加字段之前的行逐字节相同。 */
    private interface ToolSpecMixin {
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        boolean deferred();
    }

    /** 旧规则的设置（没有压缩点和系数）不写出这两个字段，和加字段之前的行逐字节相同；旧行读出来是 0（compaction-threshold）。 */
    private interface CompactionSettingsMixin {
        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        long point();

        @JsonInclude(JsonInclude.Include.NON_DEFAULT)
        double bytesPerToken();
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ImageRef.class, name = "image"),
        @JsonSubTypes.Type(value = FileRef.class, name = "file")
    })
    private interface AttachmentRefMixin {}

    /** 没有附件时不写出，和加字段之前的行逐字节相同；旧行读出来是空列表。 */
    private interface AttachmentsMixin {
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<AttachmentRef> attachments();
    }

    /** 同上，工具结果里的图片。 */
    private interface ImagesMixin {
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ImageRef> images();
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = OwnerRef.OfSession.class, name = "session"),
        @JsonSubTypes.Type(value = OwnerRef.OfCommand.class, name = "command")
    })
    private interface OwnerRefMixin {}

    private final Map<Key, Class<? extends Event>> registry = new HashMap<>();
    private final JsonMapper mapper;

    private EventCodec() {
        SimpleModule module = new SimpleModule("tantalum");
        module.addSerializer(Json.class, new JsonValueSerializer());
        module.addDeserializer(Json.class, new JsonValueDeserializer());
        mapper = JsonMapper.builder()
                .addModule(module)
                .addMixIn(ContentBlock.class, ContentBlockMixin.class)
                .addMixIn(OwnerRef.class, OwnerRefMixin.class)
                .addMixIn(AttachmentRef.class, AttachmentRefMixin.class)
                .addMixIn(UserPrompt.class, AttachmentsMixin.class)
                .addMixIn(SteerSubmitted.class, AttachmentsMixin.class)
                .addMixIn(FollowUpSubmitted.class, AttachmentsMixin.class)
                .addMixIn(ToolFinished.class, ImagesMixin.class)
                .addMixIn(ToolResultBlock.class, ImagesMixin.class)
                .addMixIn(ToolSpec.class, ToolSpecMixin.class)
                .addMixIn(CompactionSettings.class, CompactionSettingsMixin.class)
                .changeDefaultPropertyInclusion(value -> value
                        .withValueInclusion(JsonInclude.Include.NON_NULL)
                        .withContentInclusion(JsonInclude.Include.NON_NULL))
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                // 只写记录的字段：isEnded() 这类判断方法不是数据，不进 journal 和接口。
                .changeDefaultVisibility(visibility -> visibility.withIsGetterVisibility(JsonAutoDetect.Visibility.NONE))
                .build();
    }

    public static EventCodec withBuiltins() {
        EventCodec codec = new EventCodec();
        codec.register(UserPrompt.TYPE, 1, UserPrompt.class);
        codec.register(SteerSubmitted.TYPE, 1, SteerSubmitted.class);
        codec.register(FollowUpSubmitted.TYPE, 1, FollowUpSubmitted.class);
        codec.register(Cancel.TYPE, 1, Cancel.class);
        codec.register(ContextAssembled.TYPE, 1, ContextAssembled.class);
        codec.register(ModelReplied.TYPE, 1, ModelReplied.class);
        codec.register(ModelFailed.TYPE, 1, ModelFailed.class);
        codec.register(ModelInterrupted.TYPE, 1, ModelInterrupted.class);
        codec.register(ToolFinished.TYPE, 1, ToolFinished.class);
        codec.register(TimerFired.TYPE, 1, TimerFired.class);
        codec.register(WorkDone.TYPE, 1, WorkDone.class);
        codec.register(CommandDropped.TYPE, 1, CommandDropped.class);
        codec.register(CompactRequested.TYPE, 1, CompactRequested.class);
        codec.register(RunInterrupted.TYPE, 1, RunInterrupted.class);
        return codec;
    }

    /** 带同样配置的 mapper，接口返回 prompt 和回复时用，保证和 journal 里的格式一致。 */
    public JsonMapper mapper() {
        return mapper;
    }

    public void register(String type, int version, Class<? extends Event> eventClass) {
        if (registry.putIfAbsent(new Key(type, version), eventClass) != null) {
            throw new IllegalStateException("event already registered: " + type + " v" + version);
        }
    }

    public String encode(Event event) {
        return mapper.writeValueAsString(event);
    }

    public Event decode(String type, int version, String json) {
        Class<? extends Event> eventClass = registry.get(new Key(type, version));
        if (eventClass == null) {
            throw new IllegalArgumentException("unregistered event: " + type + " v" + version);
        }
        try {
            return mapper.readValue(json, eventClass);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("corrupt payload for " + type + " v" + version, failure);
        }
    }

    private static final class JsonValueSerializer extends ValueSerializer<Json> {
        @Override
        public void serialize(Json value, JsonGenerator out, SerializationContext context) {
            switch (value) {
                case Json.Null ignored -> out.writeNull();
                case Json.Bool bool -> out.writeBoolean(bool.value());
                case Json.Num num -> out.writeNumber(num.value());
                case Json.Str str -> out.writeString(str.value());
                case Json.Arr arr -> {
                    out.writeStartArray();
                    for (Json item : arr.items()) {
                        serialize(item, out, context);
                    }
                    out.writeEndArray();
                }
                case Json.Obj obj -> {
                    out.writeStartObject();
                    for (Map.Entry<String, Json> field : obj.fields().entrySet()) {
                        out.writeName(field.getKey());
                        serialize(field.getValue(), out, context);
                    }
                    out.writeEndObject();
                }
            }
        }
    }

    private static final class JsonValueDeserializer extends ValueDeserializer<Json> {
        @Override
        public Json deserialize(JsonParser in, DeserializationContext context) {
            return JsonText.read(in);
        }

        /** JSON 里的 null 是 {@code Json.Null}。 */
        @Override
        public Json getNullValue(DeserializationContext context) {
            return new Json.Null();
        }

        /** 字段缺失表示 Java 的 null，如无效的工具参数。 */
        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }
}
