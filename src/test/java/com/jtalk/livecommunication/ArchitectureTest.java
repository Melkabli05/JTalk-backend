package com.jtalk.livecommunication;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Layering of the control plane: controller / listener -> domain.service -> domain.repository / integration
 * -> domain.entity. Nothing reaches upwards. The mixer (media plane) is self-contained.
 */
@AnalyzeClasses(packages = "com.jtalk.livecommunication", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.jtalk.livecommunication.";
    private static final String CONTROLLER = ROOT + "controller..";
    private static final String LISTENER = ROOT + "listener..";
    private static final String INTEGRATION = ROOT + "integration..";
    private static final String DTO = ROOT + "domain.dto..";
    private static final String SERVICE = ROOT + "domain.service..";
    private static final String SERVICE_IMPL = ROOT + "domain.service.impl..";
    private static final String REPOSITORY = ROOT + "domain.repository..";
    private static final String MIXER = ROOT + "mixer..";

    // ---- control plane layering ----------------------------------------------------------------

    @ArchTest
    static final ArchRule controllers_and_listeners_only_use_services = noClasses()
            .that().resideInAnyPackage(CONTROLLER, LISTENER)
            .should().dependOnClassesThat().resideInAnyPackage(INTEGRATION, ROOT + "mixer..");

    @ArchTest
    static final ArchRule controllers_do_not_use_repositories = noClasses()
            .that().resideInAPackage(CONTROLLER)
            .should().dependOnClassesThat().resideInAPackage(REPOSITORY);

    @ArchTest
    static final ArchRule services_do_not_use_controllers_listeners_or_dtos = noClasses()
            .that().resideInAPackage(SERVICE)
            .should().dependOnClassesThat().resideInAnyPackage(CONTROLLER, LISTENER, DTO);

    @ArchTest
    static final ArchRule repositories_do_not_use_upper_layers = noClasses()
            .that().resideInAPackage(REPOSITORY)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, CONTROLLER, LISTENER, INTEGRATION, DTO);

    @ArchTest
    static final ArchRule integrations_are_passive_clients_called_by_services = noClasses()
            .that().resideInAPackage(INTEGRATION)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, CONTROLLER, LISTENER, REPOSITORY, DTO);

    @ArchTest
    static final ArchRule entities_enums_and_models_are_plain = noClasses()
            .that().resideInAnyPackage(ROOT + "domain.entity..", ROOT + "domain.enums..", ROOT + "domain.model..")
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, REPOSITORY, CONTROLLER, LISTENER, INTEGRATION, DTO);

    @ArchTest
    static final ArchRule dtos_do_not_use_services_or_repositories = noClasses()
            .that().resideInAPackage(DTO)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, REPOSITORY, CONTROLLER, INTEGRATION);

    @ArchTest
    static final ArchRule only_service_interfaces_are_used_outside_service_impl = noClasses()
            .that().resideOutsideOfPackage(SERVICE_IMPL)
            .should().dependOnClassesThat().resideInAPackage(SERVICE_IMPL);

    // ---- media plane -----------------------------------------------------------------------------

    @ArchTest
    static final ArchRule mixer_is_self_contained = noClasses()
            .that().resideInAPackage(MIXER)
            .should().dependOnClassesThat().resideInAnyPackage(
                    CONTROLLER, LISTENER, INTEGRATION, SERVICE, REPOSITORY, DTO, ROOT + "domain.entity..",
                    ROOT + "config..", ROOT + "security..", ROOT + "exception..", "io.micronaut..");

    @ArchTest
    static final ArchRule nothing_depends_on_the_mixer = noClasses()
            .that().resideOutsideOfPackage(MIXER)
            .should().dependOnClassesThat().resideInAPackage(MIXER);
}
