package tantalum.examples;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 写插件只需要公开接口，碰不到内部实现。 */
@AnalyzeClasses(packages = "tantalum.examples", importOptions = ImportOption.DoNotIncludeTests.class)
class PluginBoundaryTest {

    @ArchTest
    static final ArchRule examplesOnlyUsePublicPluginApi = classes()
            .that().resideInAPackage("tantalum.examples..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "tantalum.examples..", "tantalum.value..", "tantalum.kernel.hook..", "tantalum.shell.plugin..", "java..");
}
