package tantalum.shell.journal;

/** 一个会话文件中间损坏。只拒绝这个会话，不影响其他会话。消息指出文件和行号。 */
public final class SessionCorruptException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SessionCorruptException(String message, Throwable cause) {
        super(message, cause);
    }
}
