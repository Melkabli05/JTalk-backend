package com.jtalk.livecommunication;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Layering: controller -> service -> repository / integration -> domain. Nothing reaches upwards, and only service interfaces (not their
 * implementations) are used across packages. The Redis expiry listener lives with the repositories and reports through an event.
 */
@AnalyzeClasses(packages = "com.jtalk.livecommunication", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.jtalk.livecommunication.";
    private static final String CONTROLLER = ROOT + "controller..";
    private static final String INTEGRATION = ROOT + "integration..";
    private static final String DTO = ROOT + "dto..";
    private static final String SERVICE = ROOT + "service..";
    private static final String SERVICE_IMPL = ROOT + "service.impl..";
    private static final String REPOSITORY = ROOT + "repository..";
    private static final String INTEGRATION_IMPL = ROOT + "integration.impl..";
    private static final String ENTITY = ROOT + "domain.entity..";

    @ArchTest
    static final ArchRule controllers_use_services_not_repositories_or_integrations = noClasses()
            .that().resideInAPackage(CONTROLLER)
            .should().dependOnClassesThat().resideInAnyPackage(REPOSITORY, INTEGRATION)
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Validator");

    @ArchTest
    static final ArchRule validators_only_look_at_the_domain_and_never_back_into_services = noClasses()
            .that().haveSimpleNameEndingWith("Validator").or().haveSimpleNameEndingWith("ValidatorImpl")
            .should().dependOnClassesThat().resideInAnyPackage(CONTROLLER, INTEGRATION, DTO)
            .orShould().dependOnClassesThat().haveNameMatching(".*\\.\\w+Service(Impl)?");

    @ArchTest
    static final ArchRule services_do_not_use_controllers = noClasses()
            .that().resideInAPackage(SERVICE)
            .should().dependOnClassesThat().resideInAPackage(CONTROLLER);

    @ArchTest
    static final ArchRule repositories_do_not_use_upper_layers = noClasses()
            .that().resideInAPackage(REPOSITORY)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, CONTROLLER, INTEGRATION, DTO);

    @ArchTest
    static final ArchRule the_gateway_is_a_passive_client_of_the_media_server = noClasses()
            .that().resideInAPackage(INTEGRATION)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, CONTROLLER, REPOSITORY, DTO, ENTITY);

    @ArchTest
    static final ArchRule entities_enums_and_models_are_plain = noClasses()
            .that().resideInAnyPackage(ENTITY, ROOT + "domain.enums..", ROOT + "domain.model..")
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, REPOSITORY, CONTROLLER, INTEGRATION, DTO);

    @ArchTest
    static final ArchRule dtos_do_not_use_services_or_repositories = noClasses()
            .that().resideInAPackage(DTO)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, REPOSITORY, CONTROLLER, INTEGRATION);

    @ArchTest
    static final ArchRule only_interfaces_are_used_outside_the_implementation_packages = noClasses()
            .that().resideOutsideOfPackages(SERVICE_IMPL, INTEGRATION_IMPL)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE_IMPL, INTEGRATION_IMPL);
}
