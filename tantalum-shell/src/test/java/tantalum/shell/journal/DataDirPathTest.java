package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.attachment.ImageNormalizer;
import tantalum.shell.auth.UserStore;
import tantalum.shell.store.SessionIndex;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * windows-server 需求 1.5：数据目录的路径含空格和中文（{@code C:\Users\张 三\…}）时，账号库、索引、会话文件、附件照常读写。
 * 所有平台都跑；真正的风险在 Windows 上 {@code sqlite-jdbc} 把路径转成本机编码那一步。
 */
class DataDirPathTest {

    private static final Principal ALICE = new Principal("alice");

    @TempDir
    Path temp;

    @Test
    void aDataDirectoryWithSpacesAndChineseWorksForEveryStore() throws Exception {
        Path data = temp.resolve("数据 目录");
        EventCodec codec = EventCodec.withBuiltins();
        try (UserStore users = UserStore.open(data)) {
            users.add("alice", "密码 pw");
        }
        SessionId session = new SessionId("s-1");
        SessionIndex index = SessionIndex.open(data);
        assertTrue(index.claim(session, ALICE));
        index.close();
        SessionPaths paths = new SessionPaths(data);
        try (SessionFiles files = new SessionFiles(paths, codec, 4, (principal, id, seq, offset) -> { })) {
            files.append(ALICE, session, List.of(ResultWriter.noEffect(SessionFileTest.event(ALICE, session, 1, "你好"))));
        }
        AttachmentLimits limits = AttachmentLimits.defaults();
        AttachmentRef ref = new AttachmentStore(data, limits, new ImageNormalizer(limits))
                .upload(ALICE, "说明 a.txt", "text/plain", new ByteArrayInputStream("内容".getBytes(StandardCharsets.UTF_8)), 6);

        try (UserStore users = UserStore.open(data)) {
            assertTrue(users.verify("alice", "密码 pw"));
        }
        SessionIndex reopened = SessionIndex.open(data);
        assertEquals(ALICE, reopened.session(session).orElseThrow().principal());
        reopened.close();
        assertEquals("你好", ((UserPrompt) new SessionFileReader(codec).readAll(paths.sessionFile(ALICE, session)).getFirst().event()).text());
        assertEquals("内容", new String(new AttachmentStore(data, limits, new ImageNormalizer(limits)).bytes(ALICE, ref.id()), StandardCharsets.UTF_8));
    }
}
