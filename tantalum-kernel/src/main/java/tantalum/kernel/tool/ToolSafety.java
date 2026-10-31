package tantalum.kernel.tool;

import tantalum.value.json.Json;

/** 判断一次工具调用能否与其他调用同时执行。未声明、抛异常或返回 false 都按独占处理。 */
@FunctionalInterface
public interface ToolSafety {

    boolean isConcurrencySafe(String tool, Json arguments);
}
