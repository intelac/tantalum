package tantalum.shell.access;

import tantalum.shell.plugin.Tool;

/**
 * 联网工具（{@code web_fetch} 和 11 个 {@code browser_*}）实现它，{@link WebAccess} 按这个类型认工具。只看 {@link Tool#server()}
 * 的话，一个碰巧叫 {@code browser} 或 {@code web-fetch} 的插件工具（{@code server()} 默认就是工具名）会被当成联网工具，
 * 跟着联网的开关走、来源记成 {@code web}。同本机工作区按 {@code LocalTool} 类型认。
 * 实现它的工具，{@code server()} 必须是 {@link WebAccess#FETCH} 或 {@link WebAccess#BROWSER}：子开关按它选。
 */
public interface WebTool extends Tool {}
