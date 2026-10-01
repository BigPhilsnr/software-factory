package dev.shortener;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The packages tell one story in one direction: shorten, redirect, analytics, on top of link and platform. */
@AnalyzeClasses(packages = "dev.shortener", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageDependencyTest {
    private static final String SHORTEN = "dev.shortener.shorten..";
    private static final String REDIRECT = "dev.shortener.redirect..";
    private static final String ANALYTICS = "dev.shortener.analytics..";
    private static final String LINK = "dev.shortener.link..";
    private static final String PLATFORM = "dev.shortener.platform..";

    @ArchTest
    static final ArchRule packagesAreFreeOfCycles =
            slices().matching("dev.shortener.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule platformKnowsNothingOfTheStory = noClasses()
            .that()
            .resideInAPackage(PLATFORM)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(SHORTEN, REDIRECT, ANALYTICS, LINK);

    @ArchTest
    static final ArchRule linkIsTheSharedFoundation = noClasses()
            .that()
            .resideInAPackage(LINK)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(SHORTEN, REDIRECT, ANALYTICS, PLATFORM);

    @ArchTest
    static final ArchRule shorteningStandsAlone = noClasses()
            .that()
            .resideInAPackage(SHORTEN)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(REDIRECT, ANALYTICS);

    @ArchTest
    static final ArchRule redirectingNeverWaitsOnAnalytics = noClasses()
            .that()
            .resideInAPackage(REDIRECT)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(SHORTEN, ANALYTICS);

    @ArchTest
    static final ArchRule analyticsOnlyObservesRedirects = noClasses()
            .that()
            .resideInAPackage(ANALYTICS)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(SHORTEN);
}
