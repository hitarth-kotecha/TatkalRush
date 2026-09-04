package io.tatkalrush.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * AC-0.4. Module boundary enforcement.
 *
 * <p><b>ArchUnit is the second line of defence, not the first.</b> Under Maven, a
 * module that does not declare another cannot resolve its classes at all, so a
 * direct inward-pointing dependency is a <em>compile error</em> (SDD §8.2, DD-005).
 * ArchUnit's job is what compilation cannot catch: chiefly a framework annotation
 * reaching {@code domain} through a transitive dependency, which compiles perfectly
 * and quietly destroys the framework-free property that makes {@code domain} the
 * reference specification of the allocation algorithm.
 *
 * <p>ArchUnit is used here as a plain library rather than through its JUnit engine,
 * because {@code archunit-junit5} requires JUnit platform 1.14.4 while Spring Boot
 * 4.0.8 supplies 6.0.3. The core artifact depends on slf4j-api alone (DD-020).
 */
class ModuleBoundaryTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("io.tatkalrush");
    }

    /**
     * Guards every other rule in this class. An empty import set makes all of them
     * pass vacuously - the same failure shape as surefire silently skipping a test
     * class, which the Phase 0 spike found the hard way.
     */
    @Test
    void importedSomethingToCheck() {
        assertTrue(
                production.size() > 0,
                "ArchUnit imported no classes; every rule below would pass vacuously");
    }

    @Test
    void domainDependsOnNothingInThisProject() {
        noClasses()
                .that()
                .resideInAPackage("io.tatkalrush.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.tatkalrush.application..",
                        "io.tatkalrush.adapters..",
                        "io.tatkalrush.admission..",
                        "io.tatkalrush.ops..")
                .because(
                        "domain is the reference specification of the allocation algorithm"
                            + " (SDD 8.2). It must be readable and testable with no infrastructure"
                            + " whatsoever.")
                .check(production);
    }

    @Test
    void domainIsFrameworkFree() {
        noClasses()
                .that()
                .resideInAPackage("io.tatkalrush.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "org.hibernate..",
                        "io.lettuce..",
                        "org.apache.kafka..")
                .because(
                        "domain carries the 85 percent coverage target and must not require a"
                            + " container to test. maven-enforcer bans these at the dependency"
                            + " level; this catches them arriving transitively.")
                .check(production);
    }

    @Test
    void applicationDependsOnNoAdapter() {
        noClasses()
                .that()
                .resideInAPackage("io.tatkalrush.application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.tatkalrush.adapters..", "io.tatkalrush.admission..")
                .because(
                        "application defines ports; adapters implement them. The arrow points"
                            + " inward, which is what makes the two allocator strategies"
                            + " interchangeable (G-2).")
                .check(production);
    }

    /**
     * The preview quarantine, found by the Phase 0 toolchain spike and not present
     * in SDD §8.5.
     *
     * <p>{@code adapters/web} is the only module compiled with the enable-preview
     * flag. A module that merely <em>compiles against</em> preview bytecode also
     * needs that flag - it fails at compile time, before any test runs, with a
     * message about class file version 69.65535. DD-004's containment argument
     * therefore holds only while nothing depends on {@code adapters/web}. That is
     * currently true by accident of the module graph; this rule makes it deliberate.
     */
    @Test
    void nothingDependsOnThePreviewEnabledModule() {
        noClasses()
                .that()
                .resideOutsideOfPackage("io.tatkalrush.adapters.web..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.tatkalrush.adapters.web..")
                .because(
                        "adapters/web is the only preview-enabled module (DD-004). Anything"
                            + " compiling against it would need --enable-preview too, spreading"
                            + " preview bytecode beyond its quarantine.")
                .check(production);
    }
}
