package tantalum.shell;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

@AnalyzeClasses(packages = "tantalum", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule valueDependsOnNoOtherLayer = noClasses()
            .that().resideInAPackage("tantalum.value..")
            .should().dependOnClassesThat().resideInAnyPackage("tantalum.kernel..", "tantalum.shell..");

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

    @ArchTest
    static final ArchRule springStaysAtTheEdge = noClasses()
            .that().resideInAPackage("tantalum.shell..")
            .and().resideOutsideOfPackages("tantalum.shell.boot..", "tantalum.shell.web..", "tantalum.shell.auth..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

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
