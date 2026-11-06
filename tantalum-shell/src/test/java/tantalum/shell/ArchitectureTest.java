package tantalum.shell;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.WorkType;

@AnalyzeClasses(packages = "tantalum", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule valueDependsOnNoOtherLayer = noClasses()
            .that().resideInAPackage("tantalum.value..")
            .should().dependOnClassesThat().resideInAnyPackage("tantalum.kernel..", "tantalum.shell..");

    /** 本机工作区的工具在用户电脑上的连接程序里执行，不能拖进服务器、内核和 Spring（local-workspace 设计“Project Structure”）。 */
    @ArchTest
    static final ArchRule localStandsAlone = noClasses()
            .that().resideInAPackage("tantalum.local..")
            .should().dependOnClassesThat().resideInAnyPackage("tantalum.shell..", "tantalum.kernel..", "org.springframework..");

    /**
     * 服务器上不读写用户的文件（local-workspace L6）：转发工具、连接和本机工作区只把调用交给电脑，自己不碰文件。
     * {@code LocalTokens} 例外：它照 {@code PluginSwitches} 的做法在数据目录里打开 {@code users.db}，碰的是服务器自己的数据。
     */
    @ArchTest
    static final ArchRule localWorkspaceTouchesNoFiles = noClasses()
            .that().resideInAPackage("tantalum.shell.local..")
            .and().doNotHaveFullyQualifiedName("tantalum.shell.local.LocalTokens")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.Files");

    @ArchTest
    static final ArchRule kernelDoesNotDependOnShell = noClasses()
            .that().resideInAPackage("tantalum.kernel..")
            .should().dependOnClassesThat().resideInAPackage("tantalum.shell..");

    @ArchTest
    static final ArchRule kernelDoesNoIo = noClasses()
            .that().resideInAPackage("tantalum.kernel..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.io..", "java.net..", "java.sql..", "java.nio.file..", "java.util.concurrent..",
                    "org.springframework..", "com.lmax..", "tools.jackson..", "com.fasterxml..");

    @ArchTest
    static final ArchRule kernelDoesNotReadTheClock = noClasses()
            .that().resideInAPackage("tantalum.kernel..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.time.Clock")
            .orShould().callMethod(java.time.Instant.class, "now")
            .orShould().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(System.class, "nanoTime");

    @ArchTest
    static final ArchRule valueHoldsOnlyRecordsInterfacesAndEnums = classes()
            .that().resideInAPackage("tantalum.value..")
            .should(new ArchCondition<JavaClass>("be a record, interface or enum") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    boolean allowed = item.isRecord() || item.isInterface() || item.isEnum();
                    events.add(new SimpleConditionEvent(item, allowed, item.getName()));
                }
            });

    @ArchTest
    static final ArchRule processorHooksStayInShell = noClasses()
            .that().resideOutsideOfPackage("tantalum.shell.plugin..")
            .should().haveSimpleName("AfterToolHook");

    @ArchTest
    static final ArchRule mcpSdkStaysInItsPackage = noClasses()
            .that().resideInAPackage("tantalum..").and().resideOutsideOfPackage("tantalum.shell.mcp..")
            .should().dependOnClassesThat().resideInAPackage("io.modelcontextprotocol..");

    /** 浏览器的驱动只在浏览器包里用（web-tools 设计“Project Structure”规则 1）：换掉 Playwright 时只改一个包。 */
    @ArchTest
    static final ArchRule playwrightStaysInTheBrowserPackage = noClasses()
            .that().resideInAPackage("tantalum..").and().resideOutsideOfPackage("tantalum.shell.browser..")
            .should().dependOnClassesThat().resideInAPackage("com.microsoft.playwright..");

    /** HTML 解析只在 {@code web_fetch} 的包里用（规则 2）。 */
    @ArchTest
    static final ArchRule jsoupStaysInTheFetchPackage = noClasses()
            .that().resideInAPackage("tantalum..").and().resideOutsideOfPackage("tantalum.shell.fetch..")
            .should().dependOnClassesThat().resideInAPackage("org.jsoup..");

    /**
     * 联网只能经出口（规则 3）：抓网页和浏览器不自己建 {@code HttpClient}、不开 {@code Socket}、不调 {@code URL.openConnection}，
     * 内网限制和“自己的端口”才不会被绕过。
     */
    @ArchTest
    static final ArchRule webToolsGoOnlyThroughTheEgress = noClasses()
            .that().resideInAnyPackage("tantalum.shell.fetch..", "tantalum.shell.browser..")
            .should().callMethodWhere(callTo(java.net.http.HttpClient.class, "newBuilder|newHttpClient"))
            .orShould().callConstructorWhere(callTo(java.net.Socket.class, "<init>"))
            .orShould().callMethodWhere(callTo(java.net.URL.class, "openConnection"));

    /**
     * 服务器不为联网写文件（规则 4）：浏览器资料在内存里，下载拒绝，截图只进附件。{@code BrowserDriver} 例外：它把 Playwright 驱动
     * 解压进数据目录，碰的是服务器自己的程序文件（同 {@code LocalTokens} 的例外）。
     */
    @ArchTest
    static final ArchRule webToolsTouchNoFiles = noClasses()
            .that().resideInAnyPackage("tantalum.shell.fetch..", "tantalum.shell.browser..")
            .and().doNotHaveFullyQualifiedName("tantalum.shell.browser.BrowserDriver")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.Files");

    /** 压缩是通用逻辑：内核不认识任何供应商的 SDK。 */
    @ArchTest
    static final ArchRule kernelKnowsNoProvider = noClasses()
            .that().resideInAPackage("tantalum.kernel..")
            .should().dependOnClassesThat().resideInAnyPackage("com.anthropic..", "com.openai..", "tantalum.shell.model..");

    /** 索引和文件都不能拖住 cleanroom：它只把变化交给监听器。 */
    @ArchTest
    static final ArchRule cleanroomDoesNoIo = noClasses()
            .that().haveFullyQualifiedName("tantalum.shell.loop.Cleanroom").or().haveFullyQualifiedName("tantalum.shell.loop.Evictor")
            .or().haveFullyQualifiedName("tantalum.shell.loop.Residency")
            .should().dependOnClassesThat().resideInAnyPackage("java.io..", "java.sql..", "java.nio.file..", "tantalum.shell.store..");

    /**
     * 恢复和查询只读结果行（record-results 需求 9.3）：它们不依赖内核，内核以后怎么改都不会改变旧会话。
     * 用内核的只有重放比较（{@code SessionReplay}、{@code ReplayCompare}、{@code RecordCheck}，检查工具用）和启动时的 {@code Backfill}
     * （它给崩溃留下的事件第一次处理）。
     */
    @ArchTest
    static final ArchRule restoringDoesNotRunTheKernel = noClasses()
            .that().haveFullyQualifiedName("tantalum.shell.store.RecordReplay")
            .or().haveFullyQualifiedName("tantalum.shell.store.ColdReads")
            .or().haveFullyQualifiedName("tantalum.shell.store.TraceQuery")
            .or().haveFullyQualifiedName("tantalum.shell.store.TurnQuery")
            .or().haveFullyQualifiedName("tantalum.shell.store.IndexCatchUp")
            .or().haveFullyQualifiedName("tantalum.shell.store.SessionQueries")
            .should().dependOnClassesThat().haveFullyQualifiedName("tantalum.kernel.Kernel")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("tantalum.shell.store.SessionReplay");

    @ArchTest
    static final ArchRule springStaysAtTheEdge = noClasses()
            .that().resideInAPackage("tantalum.shell..")
            .and().resideOutsideOfPackages("tantalum.shell.boot..", "tantalum.shell.web..", "tantalum.shell.auth..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    /**
     * 插件的唯一入口（core-tools C16）：工具、工具后处理、插件工作只能经 {@code PluginGate} 调用，它在调用前按用户的开关过滤。
     * 谁绕过它直接调用，关掉的插件就会照样执行。
     */
    @ArchTest
    static final ArchRule onlyTheGateRunsPlugins = noClasses()
            .that().doNotHaveFullyQualifiedName("tantalum.shell.access.PluginGate")
            .should().callMethodWhere(callTo(Tool.class, "run"))
            .orShould().callMethodWhere(callTo(WorkType.class, "run"))
            .orShould().callMethodWhere(callTo(AfterToolHook.class, "afterToolExecution"));

    /** 内核钩子只由 {@code HookChain} 调用，它按会话记下的插件名单过滤。 */
    @ArchTest
    static final ArchRule onlyTheHookChainRunsHooks = noClasses()
            .that().doNotHaveFullyQualifiedName("tantalum.kernel.hook.HookChain")
            .should().callMethodWhere(callTo(KernelHook.class,
                    "beforeModelCall|beforeToolCall|onFailure|beforeRunEnd|onWorkResult|beforeCompact"));

    private static DescribedPredicate<JavaCall<?>> callTo(Class<?> type, String names) {
        return JavaCall.Predicates.target(HasName.Predicates.nameMatching(names))
                .and(JavaCall.Predicates.target(HasOwner.Predicates.With.owner(JavaClass.Predicates.assignableTo(type))))
                .as("a call to " + type.getSimpleName() + "." + names);
    }

    /** 包之间单向依赖：原始记录 ← 缓存，管线 ← 组装。循环会让包名不再说明里面是什么。 */
    @ArchTest
    static final ArchRule shellPackagesHaveNoCycles = slices().matching("tantalum.shell.(*)..").should().beFreeOfCycles();

    /** 内核子包也单向依赖：转移表只认处理函数的形状，由步骤包把自己注册进去。 */
    @ArchTest
    static final ArchRule kernelPackagesHaveNoCycles = slices().matching("tantalum.kernel.(*)..").should().beFreeOfCycles();

    /** {@code Kernel} 在最上层调用步骤，子包不回头依赖它，否则又绕成环。 */
    @ArchTest
    static final ArchRule kernelRootSitsOnTop = noClasses()
            .that().resideInAPackage("tantalum.kernel.*..")
            .should().dependOnClassesThat().resideInAPackage("tantalum.kernel");

    /** 只有组装和 Web 层读全局配置；其余的包拿自己的设置记录。 */
    @ArchTest
    static final ArchRule onlyTheEdgeReadsTheConfiguration = noClasses()
            .that().resideInAPackage("tantalum.shell..")
            .and().resideOutsideOfPackages("tantalum.shell.boot..", "tantalum.shell.web..")
            .should().dependOnClassesThat().resideInAPackage("tantalum.shell.boot..");
}
