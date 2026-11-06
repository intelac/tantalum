package tantalum.local.tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import tantalum.local.fs.PrivateFiles;

/**
 * 文件工具共用的文本处理：判断二进制、严格 UTF-8 逐行读、换行风格和 BOM、原子写（需求 C3、C4、C6、C7）。
 * 换行判断和原子写的做法照 DSH {@code fs-local/src/fsio.ts:618-702,731-751}。
 */
final class TextFiles {

    /** 前多少字节里有空字节就算二进制（DSH {@code fsio.ts:18}）。 */
    static final int SNIFF_BYTES = 8 * 1024;
    /** 判断换行风格只看开头这么多字符（DSH {@code fsio.ts:731-751}）。 */
    static final int EOL_SAMPLE_CHARS = 4096;
    static final char BOM = '﻿';

    private static final String TEMP_SUFFIX = ".tantalum-tmp";

    private TextFiles() {}

    /** 一份文本：{@code content} 已去掉 BOM、换行统一成 LF。 */
    record Text(String content, boolean bom, boolean crlf) {

        /** 按原来的换行风格和 BOM 还原成要写回的字节。 */
        byte[] encode(String newContent) {
            String body = crlf ? newContent.replace("\n", "\r\n") : newContent;
            return ((bom ? String.valueOf(BOM) : "") + body).getBytes(StandardCharsets.UTF_8);
        }
    }

    /** 逐行读到的一行：{@code text} 最多 {@code maxChars} 个字符，超出的部分只计数不保留。 */
    record Line(String text, boolean truncated) {}

    static boolean isBinary(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            head = in.readNBytes(SNIFF_BYTES);
        }
        for (byte b : head) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /** 严格 UTF-8 解码，不合法的字节报 {@link CharacterCodingException}。 */
    static String decode(byte[] bytes) throws CharacterCodingException {
        return strictDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** 读整个文件：去掉 BOM、记下原来的换行风格，内容换成 LF。 */
    static Text read(Path file) throws IOException {
        String raw = decode(Files.readAllBytes(file));
        boolean bom = !raw.isEmpty() && raw.charAt(0) == BOM;
        String body = bom ? raw.substring(1) : raw;
        return new Text(toLf(body), bom, isCrlf(body));
    }

    /** 开头 {@link #EOL_SAMPLE_CHARS} 个字符里 CRLF 比单独的 LF 多就算 CRLF。 */
    static boolean isCrlf(String text) {
        int end = Math.min(text.length(), EOL_SAMPLE_CHARS);
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                if (i > 0 && text.charAt(i - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            }
        }
        return crlf > lf;
    }

    static String toLf(String text) {
        return text.replace("\r\n", "\n");
    }

    /** 严格 UTF-8 的逐行读取器：按 LF 分行，去掉行尾的 CR 和第一行开头的 BOM。 */
    static LineReader lines(Path file, int maxChars) throws IOException {
        InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        return new LineReader(new InputStreamReader(in, strictDecoder()), maxChars);
    }

    static final class LineReader implements AutoCloseable {

        private final Reader reader;
        private final int maxChars;
        private final char[] buffer = new char[64 * 1024];
        private int position;
        private int limit;
        private boolean first = true;
        private boolean cut;

        private LineReader(Reader reader, int maxChars) {
            this.reader = reader;
            this.maxChars = maxChars;
        }

        /** 下一行；读完时返回 null。文件以换行结尾时，最后的空串不算一行。 */
        Line next() throws IOException {
            StringBuilder line = new StringBuilder();
            cut = false;
            boolean any = false;
            boolean pendingCr = false;
            while (true) {
                if (position == limit) {
                    limit = reader.read(buffer);
                    position = 0;
                    if (limit < 0) {
                        limit = 0;
                        if (!any) {
                            return null;
                        }
                        if (pendingCr) {
                            append(line, '\r');
                        }
                        return new Line(line.toString(), cut);
                    }
                }
                char c = buffer[position++];
                if (first) {
                    first = false;
                    if (c == BOM) {
                        continue;
                    }
                }
                any = true;
                if (c == '\n') {
                    return new Line(line.toString(), cut);
                }
                if (pendingCr) {
                    append(line, '\r');
                }
                pendingCr = c == '\r';
                if (!pendingCr) {
                    append(line, c);
                }
            }
        }

        /** 一旦截断，这一行后面的字符都不要；不在代理对中间截断。 */
        private void append(StringBuilder line, char c) {
            if (cut) {
                return;
            }
            if (line.length() >= maxChars || (line.length() == maxChars - 1 && Character.isHighSurrogate(c))) {
                cut = true;
                return;
            }
            line.append(c);
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    /** 建上级目录，新目录 700。 */
    static void createParents(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            PrivateFiles.createDirectories(parent);
        }
    }

    /**
     * 写临时文件、落盘、改名替换，读的人不会看到写了一半的文件。已有文件保留原来的权限，新文件 600。
     * 临时文件和目标在同一个目录，改名不跨文件系统。
     */
    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        Path temp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + TEMP_SUFFIX);
        try {
            try {
                PrivateFiles.createFile(temp);
            } catch (FileAlreadyExistsException impossible) {
                throw new IOException("temporary file already exists", impossible);
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) {
                    channel.write(data);
                }
                channel.force(true);
            }
            if (existed) {
                PrivateFiles.copyPermissions(target, temp);
            }
            PrivateFiles.replace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static CharsetDecoder strictDecoder() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }
}
