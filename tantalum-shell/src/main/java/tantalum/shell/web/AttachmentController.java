package tantalum.shell.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.attachment.AttachmentRejected;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.attachment.FileNames;
import tantalum.shell.boot.Engine;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 附件的上传和读取（设计第 2 节）。上传的请求体是原始字节；读取只在当前用户自己的目录里找，别人的和不存在一样返回 404。
 * 下载的内容一律 {@code nosniff} 加沙箱 CSP，非图片一律当二进制下载，HTML、SVG 不会在页面里执行。
 */
@RestController
public class AttachmentController {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentController.class);
    private static final String CACHE = "private, max-age=31536000, immutable";

    private final Engine engine;
    private final WebJson json;

    public AttachmentController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    /** {@code name} 是浏览器报的文件名，{@code type} 是浏览器报的类型（只用于文件的显示）。返回附件引用。 */
    @PostMapping("/api/sessions/{session}/attachments")
    ResponseEntity<String> upload(@PathVariable String session, @RequestParam(required = false) String name,
                                  @RequestParam(required = false) String type, HttpServletRequest request) {
        if (!isOctetStream(request.getContentType())) {
            return json.error(415, ErrorCode.REQUEST_INVALID, "the body must be raw bytes (application/octet-stream)");
        }
        Optional<SessionId> id = ApiController.sessionId(session);
        if (id.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid session id");
        }
        Principal user = WebJson.currentUser();
        int access = engine.intake().access(user, id.get());
        if (access != 200) {
            return access == 404 ? json.error(ErrorCode.SESSION_NOT_FOUND, "no such session") : json.error(access, ErrorCode.SESSION_UNAVAILABLE, "session unavailable");
        }
        try (InputStream body = request.getInputStream()) {
            AttachmentRef ref = engine.attachments().upload(user, name, type, body, request.getContentLengthLong());
            return json.json(200, describe(ref));
        } catch (AttachmentRejected rejected) {
            return json.error(status(rejected.reason()), ErrorCode.of(rejected.reason()), rejected.getMessage(), rejected.params());
        } catch (IOException failure) {
            LOG.warn("attachment upload failed: {}", failure.toString());
            return json.error(ErrorCode.ATTACHMENT_NOT_STORED, "the attachment was not stored");
        }
    }

    /** 读回附件。{@code name} 是下载时的文件名；{@code download=1} 时图片也作为下载。 */
    @GetMapping("/api/attachments/{id}")
    ResponseEntity<?> read(@PathVariable String id, @RequestParam(required = false) String name,
                           @RequestParam(required = false) String download) {
        Optional<AttachmentStore.Stored> stored;
        try {
            stored = engine.attachments().find(WebJson.currentUser(), id);
        } catch (IOException failure) {
            LOG.warn("attachment read failed: {}", failure.toString());
            return json.error(ErrorCode.ATTACHMENT_READ_FAILED, "the attachment could not be read");
        }
        if (stored.isEmpty()) {
            return json.error(ErrorCode.ATTACHMENT_NOT_FOUND, "no such attachment");
        }
        AttachmentStore.Meta meta = stored.get().meta();
        boolean image = "image".equals(meta.kind());
        boolean inline = image && !"1".equals(download);
        String filename = FileNames.clean(name, image);
        return ResponseEntity.ok()
                .contentType(image ? MediaType.parseMediaType(meta.mediaType()) : MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(meta.bytes())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, CACHE)
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encode(filename))
                .body(new FileSystemResource(stored.get().file()));
    }

    /** 接口和推送里附件引用的形状：{@code kind} 在前，没有值的字段不写。 */
    static Map<String, Object> describe(AttachmentRef ref) {
        Map<String, Object> view = new LinkedHashMap<>();
        switch (ref) {
            case ImageRef image -> {
                view.put("kind", "image");
                view.put("id", image.id());
                view.put("name", image.name());
                view.put("mediaType", image.mediaType());
                view.put("bytes", image.bytes());
                view.put("width", image.width());
                view.put("height", image.height());
                if (image.sourceWidth() != null) {
                    view.put("sourceWidth", image.sourceWidth());
                    view.put("sourceHeight", image.sourceHeight());
                }
            }
            case FileRef file -> {
                view.put("kind", "file");
                view.put("id", file.id());
                view.put("name", file.name());
                view.put("mediaType", file.mediaType());
                view.put("bytes", file.bytes());
                if (file.pages() != null) {
                    view.put("pages", file.pages());
                }
            }
        }
        return view;
    }

    static int status(AttachmentRejected.Reason reason) {
        return switch (reason) {
            case TOO_LARGE, IMAGE_FILE_TOO_LARGE, QUOTA_FULL -> 413;
            case IMAGE_TOO_LARGE, IMAGE_TOO_MANY_PIXELS, INVALID_IMAGE -> 422;
            case BUSY -> 503;
        };
    }

    private static boolean isOctetStream(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_OCTET_STREAM.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (InvalidMimeTypeException e) {
            return false;
        }
    }

    /** RFC 5987 的 {@code filename*}：除 attr-char 之外的字节都写成 {@code %XX}。 */
    private static String encode(String filename) {
        StringBuilder out = new StringBuilder();
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "!#$&+-.^_`|~".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
