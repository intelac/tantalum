package tantalum.shell.store;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.journal.StoreFormat;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.Awaiting;
import tantalum.value.state.Deciding;
import tantalum.value.state.Ended;
import tantalum.value.state.RunPhase;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 读写快照。文件两行：第一行是版本、位置和校验和，第二行是内容。写临时文件再重命名，所以崩溃只留下旧快照。
 * 任何一项校验不过都当作没有快照并记警告，调用方回到全量重放。
 */
public final class SnapshotStore {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotStore.class);
    private static final String DIR_MODE = "rwx------";
    private static final String FILE_MODE = "rw-------";

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Deciding.class, name = "deciding"),
        @JsonSubTypes.Type(value = Awaiting.class, name = "awaiting"),
        @JsonSubTypes.Type(value = Ended.class, name = "ended")
    })
    private interface RunPhaseMixin {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TranscriptItem.UserMessage.class, name = "user"),
        @JsonSubTypes.Type(value = TranscriptItem.Text.class, name = "text"),
        @JsonSubTypes.Type(value = TranscriptItem.Thinking.class, name = "thinking"),
        @JsonSubTypes.Type(value = TranscriptItem.ToolStep.class, name = "tool"),
        @JsonSubTypes.Type(value = TranscriptItem.RunStatus.class, name = "run"),
        @JsonSubTypes.Type(value = TranscriptItem.CompactionMark.class, name = "compaction")
    })
    private interface ItemMixin {}

    private record Header(int storage, int semantics, long seq, long offset, String checksum) {}

    private record Body(SessionState session, List<RunState> runs, Transcript.Saved transcript) {}

    private final SessionPaths paths;
    private final JsonMapper mapper;

    public SnapshotStore(SessionPaths paths, EventCodec codec) {
        this.paths = paths;
        this.mapper = codec.mapper().rebuild()
                .addMixIn(RunPhase.class, RunPhaseMixin.class)
                .addMixIn(TranscriptItem.class, ItemMixin.class)
                .build();
    }

    String encode(SessionSnapshot snapshot) {
        String body = mapper.writeValueAsString(new Body(snapshot.session(), snapshot.runs(), snapshot.transcript()));
        Header header = new Header(StoreFormat.STORAGE, StoreFormat.SEMANTICS, snapshot.seq(), snapshot.offset(), checksum(body));
        return mapper.writeValueAsString(header) + "\n" + body + "\n";
    }

    /** 内容校验不过时抛 {@link IllegalArgumentException}。 */
    SessionSnapshot decode(String text) {
        int split = text.indexOf('\n');
        if (split < 0 || !text.endsWith("\n")) {
            throw new IllegalArgumentException("incomplete snapshot");
        }
        String body = text.substring(split + 1, text.length() - 1);
        try {
            Header header = mapper.readValue(text.substring(0, split), Header.class);
            if (header.storage() != StoreFormat.STORAGE || header.semantics() != StoreFormat.SEMANTICS) {
                throw new IllegalArgumentException("snapshot versions " + header.storage() + "/" + header.semantics());
            }
            if (!checksum(body).equals(header.checksum())) {
                throw new IllegalArgumentException("checksum mismatch");
            }
            Body parsed = mapper.readValue(body, Body.class);
            return new SessionSnapshot(parsed.session(), parsed.runs(), parsed.transcript(), header.seq(), header.offset());
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("unreadable snapshot", failure);
        }
    }

    /** 失败时旧快照还在。 */
    public void write(Principal principal, SessionSnapshot snapshot) throws IOException {
        Path file = paths.snapshotFile(principal, snapshot.session().id());
        createPrivateDirs(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "snapshot", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_MODE)));
        try {
            Files.writeString(temp, encode(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void createPrivateDirs(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(DIR_MODE)));
        }
    }

    /** 可用的快照：校验和、版本都对，身份相符，位置在文件内且那一行的序号相符，并且早于 {@code beforeSeq}。 */
    public Optional<SessionSnapshot> load(Principal principal, SessionId session, long beforeSeq) {
        Path file = paths.snapshotFile(principal, session);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            SessionSnapshot snapshot = decode(Files.readString(file, StandardCharsets.UTF_8));
            if (!snapshot.session().id().equals(session) || !snapshot.session().principal().equals(principal)) {
                throw new IllegalArgumentException("snapshot belongs to another session");
            }
            if (snapshot.seq() >= beforeSeq) {
                throw new IllegalArgumentException("snapshot seq " + snapshot.seq() + " is not before " + beforeSeq);
            }
            long atOffset = SessionFileReader.seqEndingAt(paths.sessionFile(principal, session), snapshot.offset());
            if (atOffset != snapshot.seq()) {
                throw new IllegalArgumentException("session file has seq " + atOffset + " at offset " + snapshot.offset() + ", snapshot says " + snapshot.seq());
            }
            return Optional.of(snapshot);
        } catch (IOException | RuntimeException unusable) {
            LOG.warn("ignoring snapshot {}, replaying the whole session: {}", file, unusable.toString());
            return Optional.empty();
        }
    }

    private static String checksum(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
