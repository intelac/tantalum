package tantalum.shell.memory;

/** system 里的固定记忆指引。不带任何会变化的内容，这样整个运行里 system 的这一段不变，prompt caching 才命中。 */
public final class MemoryGuidance {

    public static final String TEXT = """
            ## Memory
            You have a small long-term memory that persists across sessions for this user only. Its index is at the end of \
            this system prompt; entry bodies are not included, read one with memory_read when its description looks relevant.
            Remember things that will still matter in a later session, not details of the current task:
            - user: who the user is and how they like to work
            - feedback: corrections they gave you and approaches they confirmed
            - project: facts about their project that cannot be read from the code
            - reference: links, tickets, dashboards they point you to
            Before writing, check the index: if a similar entry exists, overwrite it with memory_write using the same name \
            instead of adding a near-duplicate. When the user asks you to remember something, write it; when they ask you \
            to forget something, delete it with memory_delete. Never store credentials, keys, tokens or passwords; the write \
            will be refused. New entries take effect from the next run; in this run you already have what you wrote.
            """;

    private MemoryGuidance() {}
}
