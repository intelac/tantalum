package tantalum.shell.journal;

import tantalum.value.decision.Fact;
import tantalum.value.id.RunRef;
import tools.jackson.databind.JsonNode;

/**
 * 结果行里这个版本不认识的判断（更新的版本写的，或卸载了的插件）。原样保留，再写出时逐字节不变；
 * 只用于显示，不影响状态，所以会话照常载入。
 */
public record UnknownFact(String type, RunRef run, JsonNode raw) implements Fact {}
