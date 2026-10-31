package tantalum.shell.journal;

/** journal 无法可靠读取，进程必须拒绝启动。消息指出位置。 */
public final class JournalCorruptException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JournalCorruptException(String message, Throwable cause) {
        super(message, cause);
    }
}
