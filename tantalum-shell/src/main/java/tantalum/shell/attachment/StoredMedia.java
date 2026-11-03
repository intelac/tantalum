package tantalum.shell.attachment;

import java.io.IOException;
import tantalum.shell.model.AttachmentMedia;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/** 一个运行的附件内容：从运行所属用户的附件目录取，图片取请求版本（{@link RequestImages#MODEL}）。 */
public final class StoredMedia implements AttachmentMedia {

    private final AttachmentStore store;
    private final RequestImages images;
    private final Principal principal;
    private final SessionId session;

    public StoredMedia(AttachmentStore store, RequestImages images, Principal principal, SessionId session) {
        this.store = store;
        this.images = images;
        this.principal = principal;
        this.session = session;
    }

    @Override
    public Image image(ImageRef ref) {
        try {
            RequestImages.Version version = images.of(principal, ref, RequestImages.MODEL);
            return new Image(version.bytes(), version.mediaType());
        } catch (IOException failure) {
            throw unavailable(ref, failure);
        }
    }

    @Override
    public byte[] file(FileRef ref) {
        try {
            return store.bytes(principal, ref.id());
        } catch (IOException failure) {
            throw unavailable(ref, failure);
        }
    }

    private Unavailable unavailable(AttachmentRef ref, IOException failure) {
        return new Unavailable("attachment " + ref.id() + " (\"" + ref.name() + "\") in session " + session.value()
                + " is missing or damaged: " + failure.getMessage(), failure);
    }
}
