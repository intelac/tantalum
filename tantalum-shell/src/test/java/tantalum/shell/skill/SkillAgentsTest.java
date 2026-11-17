package tantalum.shell.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import tantalum.value.model.ModelChoice;

/** 技能当作任务的 agent（subagent 需求 8）：属性块里的 {@code model: 路线/模型} 怎么读。 */
class SkillAgentsTest {

    @Test
    void theModelIsWrittenAsRouteSlashModel() {
        assertEquals(new ModelChoice("claude", "claude-haiku-5-5", null), SkillAgents.model("claude/claude-haiku-5-5"));
        assertEquals(new ModelChoice("local", "qwen/next", null), SkillAgents.model(" local / qwen/next ".strip()), "only the first slash splits");
        // 没写、写得不对：当没写，用放出任务的那一轮的模型
        assertNull(SkillAgents.model(null));
        assertNull(SkillAgents.model("claude-haiku-5-5"));
        assertNull(SkillAgents.model("/claude-haiku-5-5"));
        assertNull(SkillAgents.model("claude/"));
    }
}
