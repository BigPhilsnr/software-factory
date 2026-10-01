package dev.softwarefactory.architecture;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The package story is also the dependency direction:
 * operator -> run -> {generation, validation, candidate, audit, governance, scenario} -> platform.
 */
@AnalyzeClasses(packages = "dev.softwarefactory", importOptions = ImportOption.DoNotIncludeTests.class)
class StoryArchitectureTest {
    private static final String OPERATOR = "Operator";
    private static final String RUN = "Run";
    private static final String GENERATION = "Generation";
    private static final String VALIDATION = "Validation";
    private static final String CANDIDATE = "Candidate";
    private static final String AUDIT = "Audit";
    private static final String GOVERNANCE = "Governance";
    private static final String SCENARIO = "Scenario";
    private static final String PLATFORM = "Platform";

    @ArchTest
    static final ArchRule chaptersOnlyDependDownTheStory = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer(OPERATOR)
            .definedBy("dev.softwarefactory.operator..")
            .layer(RUN)
            .definedBy("dev.softwarefactory.run..")
            .layer(GENERATION)
            .definedBy("dev.softwarefactory.generation..")
            .layer(VALIDATION)
            .definedBy("dev.softwarefactory.validation..")
            .layer(CANDIDATE)
            .definedBy("dev.softwarefactory.candidate..")
            .layer(AUDIT)
            .definedBy("dev.softwarefactory.audit..")
            .layer(GOVERNANCE)
            .definedBy("dev.softwarefactory.governance..")
            .layer(SCENARIO)
            .definedBy("dev.softwarefactory.scenario..")
            .layer(PLATFORM)
            .definedBy("dev.softwarefactory.platform..")
            .whereLayer(OPERATOR)
            .mayNotBeAccessedByAnyLayer()
            .whereLayer(RUN)
            .mayOnlyBeAccessedByLayers(OPERATOR)
            .whereLayer(GENERATION)
            .mayOnlyBeAccessedByLayers(OPERATOR, RUN)
            .whereLayer(VALIDATION)
            .mayOnlyBeAccessedByLayers(OPERATOR, RUN)
            .whereLayer(CANDIDATE)
            .mayOnlyBeAccessedByLayers(OPERATOR, RUN, GENERATION, VALIDATION)
            .whereLayer(AUDIT)
            .mayOnlyBeAccessedByLayers(OPERATOR, RUN)
            .whereLayer(GOVERNANCE)
            .mayOnlyBeAccessedByLayers(OPERATOR, RUN, GENERATION, VALIDATION, CANDIDATE, AUDIT)
            .whereLayer(SCENARIO)
            .mayOnlyBeAccessedByLayers(OPERATOR, RUN, GENERATION);

    @ArchTest
    static final ArchRule chaptersAreFreeOfCycles =
            slices().matching("dev.softwarefactory.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule operatorSurfacesAreFreeOfCycles =
            slices().matching("dev.softwarefactory.operator.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule generationAndItsToolsAreFreeOfCycles =
            slices().matching("dev.softwarefactory.generation.(**)").should().beFreeOfCycles();
}
