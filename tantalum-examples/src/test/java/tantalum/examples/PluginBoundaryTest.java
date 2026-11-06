package tantalum.examples;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.time.Clock;
import java.time.InstantSource;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import tantalum.kernel.hook.KernelHook;

/**
 * 写插件只需要公开接口，碰不到内部实现（只看主代码）；内核钩子是纯函数，不做 I/O、不碰线程和时钟（主代码和测试代码里的钩子都看）。
 * 两类规则的分析范围不同，所以导入全部类，再由各条规则自己挑：{@code MAIN_CODE} 用的就是 {@link ImportOption.DoNotIncludeTests} 的判断。
 */
@AnalyzeClasses(packages = "tantalum.examples")
class PluginBoundaryTest {

    private static final ImportOption MAIN = new ImportOption.DoNotIncludeTests();

    private static final DescribedPredicate<JavaClass> MAIN_CODE = DescribedPredicate.describe("are main code",
            javaClass -> javaClass.getSource().map(source -> MAIN.includes(Location.of(source.getUri()))).orElse(false));

    /** 钩子本身，以及嵌在钩子里的类（枚举、switch 生成的辅助类）：后者也可能替钩子做事，一起检查。 */
    private static final DescribedPredicate<JavaClass> HOOKS = DescribedPredicate.describe("are kernel hooks or nested in one",
            PluginBoundaryTest::inHook);

    private static final List<String> PURE = List.of(
            "tantalum.value", "tantalum.kernel.hook", "java.lang", "java.util", "java.time", "java.math");

    /** 插件只用插件 SDK；把它们注册成 Spring bean 的自动配置在 {@code tantalum.examples.boot}，只有那里碰 Spring。 */
    @ArchTest
    static final ArchRule examplesOnlyUsePublicPluginApi = classes()
            .that().resideInAPackage("tantalum.examples..").and().resideOutsideOfPackage("tantalum.examples.boot..").and(MAIN_CODE)
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "tantalum.examples..", "tantalum.value..", "tantalum.kernel.hook..", "tantalum.shell.plugin..", "java..")
            .andShould().onlyDependOnClassesThat().resideOutsideOfPackage("tantalum.examples.boot..");

    /** 自动配置也只用插件 SDK 和 Spring 的配置注解，不碰 shell 的内部实现。 */
    @ArchTest
    static final ArchRule autoConfigurationOnlyUsesThePluginApiAndSpring = classes()
            .that().resideInAPackage("tantalum.examples.boot..").and(MAIN_CODE)
            .should().onlyDependOnClassesThat().resideInAnyPackage("tantalum.examples..", "tantalum.value..", "tantalum.kernel.hook..",
                    "tantalum.shell.plugin..", "java..", "org.springframework..", "org.slf4j..");

    /**
     * K5：钩子只依赖 value、{@code kernel.hook} 和 JDK 里不做 I/O 的部分（java.lang、java.util、java.time，
     * 以及 {@code Json.Num} 要用的 java.math），外加自己嵌套的类。java.io、java.nio、java.net、java.sql、tantalum.shell 都不行。
     */
    @ArchTest
    static final ArchRule hooksOnlyDependOnPureApi = classes()
            .that(HOOKS)
            .should(dependOnlyOnPureApiOrThemselves())
            .because("hooks run on the shared kernel thread and are replayed; I/O goes through NeedWork");

    /** java.lang 和 java.util 里也有不纯的：取当前时间、线程和线程池、进程、环境变量、时钟。 */
    @ArchTest
    static final ArchRule hooksDoNotTouchClocksThreadsProcessesOrTheEnvironment = noClasses()
            .that(HOOKS)
            .should().callMethodWhere(target(nameMatching("now|currentTimeMillis|nanoTime")))
            .orShould().callMethod(System.class, "getenv")
            .orShould().callMethod(System.class, "getenv", String.class)
            .orShould().dependOnClassesThat().areAssignableTo(Thread.class)
            .orShould().dependOnClassesThat().areAssignableTo(Executor.class)
            .orShould().dependOnClassesThat().areAssignableTo(InstantSource.class)
            .orShould().dependOnClassesThat().belongToAnyOf(ProcessBuilder.class, Runtime.class, Executors.class,
                    CompletableFuture.class, Timer.class, Clock.class)
            .because("time comes from context.at(); hooks must not block or escape the kernel thread");

    private static boolean inHook(JavaClass javaClass) {
        if (javaClass.isAssignableTo(KernelHook.class) && !javaClass.getName().equals(KernelHook.class.getName())) {
            return true;
        }
        return javaClass.getEnclosingClass().map(PluginBoundaryTest::inHook).orElse(false);
    }

    private static JavaClass outermost(JavaClass javaClass) {
        return javaClass.getEnclosingClass().map(PluginBoundaryTest::outermost).orElse(javaClass);
    }

    private static ArchCondition<JavaClass> dependOnlyOnPureApiOrThemselves() {
        return new ArchCondition<>("only depend on classes in " + PURE + " or nested in the same hook") {
            @Override
            public void check(JavaClass hook, ConditionEvents events) {
                for (Dependency dependency : hook.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (target.isArray()) {
                        target = target.getBaseComponentType();
                    }
                    String pkg = target.getPackageName();
                    boolean pure = target.isPrimitive() || PURE.stream().anyMatch(p -> pkg.equals(p) || pkg.startsWith(p + "."));
                    if (!pure && !outermost(target).equals(outermost(hook))) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }
}
