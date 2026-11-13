package com.jtalk.livecommunication;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Layering: controller / listener -> domain.service -> domain.repository / integration -> domain.entity. Nothing reaches
 * upwards, and only service interfaces (not their implementations) are used across packages.
 */
@AnalyzeClasses(packages = "com.jtalk.livecommunication", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.jtalk.livecommunication.";
    private static final String CONTROLLER = ROOT + "controller..";
    private static final String LISTENER = ROOT + "listener..";
    private static final String INTEGRATION = ROOT + "integration..";
    private static final String DTO = ROOT + "domain.dto..";
    private static final String MAPPER = ROOT + "domain.mapper..";
    private static final String SERVICE = ROOT + "domain.service..";
    private static final String VALIDATOR = ROOT + "domain.validator..";
    private static final String VALIDATOR_IMPL = ROOT + "domain.validator.impl..";
    private static final String SERVICE_IMPL = ROOT + "domain.service.impl..";
    private static final String REPOSITORY = ROOT + "domain.repository..";
    private static final String INTEGRATION_IMPL = ROOT + "integration.impl..";

    @ArchTest
    static final ArchRule controllers_use_services_not_repositories_or_integrations = noClasses()
            .that().resideInAPackage(CONTROLLER)
            .should().dependOnClassesThat().resideInAnyPackage(REPOSITORY, INTEGRATION, VALIDATOR);

    @ArchTest
    static final ArchRule validators_only_look_at_the_domain_and_never_back_into_services = noClasses()
            .that().resideInAPackage(VALIDATOR)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, CONTROLLER, LISTENER, INTEGRATION, DTO, MAPPER);

    @ArchTest
    static final ArchRule services_do_not_use_controllers_or_listeners = noClasses()
            .that().resideInAPackage(SERVICE)
            .should().dependOnClassesThat().resideInAnyPackage(CONTROLLER, LISTENER);

    @ArchTest
    static final ArchRule repositories_do_not_use_upper_layers = noClasses()
            .that().resideInAPackage(REPOSITORY)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, CONTROLLER, LISTENER, INTEGRATION, DTO, MAPPER);

    @ArchTest
    static final ArchRule the_gateway_is_a_passive_client_of_the_media_server = noClasses()
            .that().resideInAPackage(INTEGRATION)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, CONTROLLER, LISTENER, REPOSITORY, DTO, MAPPER, ROOT + "domain.entity..");

    @ArchTest
    static final ArchRule entities_enums_and_models_are_plain = noClasses()
            .that().resideInAnyPackage(ROOT + "domain.entity..", ROOT + "domain.enums..", ROOT + "domain.model..")
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, REPOSITORY, CONTROLLER, LISTENER, INTEGRATION, DTO, MAPPER);

    @ArchTest
    static final ArchRule dtos_do_not_use_services_or_repositories = noClasses()
            .that().resideInAPackage(DTO)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, REPOSITORY, CONTROLLER, INTEGRATION, MAPPER);

    @ArchTest
    static final ArchRule only_interfaces_are_used_outside_the_implementation_packages = noClasses()
            .that().resideOutsideOfPackages(SERVICE_IMPL, INTEGRATION_IMPL, VALIDATOR_IMPL)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE_IMPL, INTEGRATION_IMPL, VALIDATOR_IMPL);
}
