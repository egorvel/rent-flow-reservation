package com.rentflow;

import java.lang.annotation.Annotation;
import java.util.List;
import jakarta.persistence.Entity;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ArchitectureTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.rentflow");

    private static final List<Class<? extends Annotation>> MVC_MAPPING_ANNOTATIONS = List.of(
            RequestMapping.class,
            GetMapping.class,
            PostMapping.class,
            PutMapping.class,
            DeleteMapping.class,
            PatchMapping.class);

    @Test
    void layersFollowTheDefinedDependencyDirection() {
        layeredArchitecture()
                .consideringOnlyDependenciesInAnyPackage("com.rentflow..")
                .layer("Controller")
                .definedBy("..controller..")
                .layer("Converter")
                .definedBy("..converter..")
                .layer("Dto")
                .definedBy("..dto..")
                .layer("Service")
                .definedBy("..service..")
                .layer("Repository")
                .definedBy("..repository..")
                .layer("Model")
                .definedBy("..model..")
                .optionalLayer("Config")
                .definedBy("..config..")
                .optionalLayer("Util")
                .definedBy("..util..")
                .whereLayer("Controller")
                .mayOnlyAccessLayers("Converter", "Dto", "Service")
                .whereLayer("Converter")
                .mayOnlyAccessLayers("Dto", "Model")
                .whereLayer("Dto")
                .mayNotAccessAnyLayer()
                .whereLayer("Service")
                .mayOnlyAccessLayers("Model", "Repository")
                .whereLayer("Repository")
                .mayOnlyAccessLayers("Model")
                .whereLayer("Model")
                .mayNotAccessAnyLayer()
                .whereLayer("Config")
                .mayOnlyAccessLayers("Dto", "Service")
                .whereLayer("Util")
                .mayNotAccessAnyLayer()
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void productionTypesUseOnlyTheDefinedApplicationPackages() {
        classes()
                .that()
                .resideInAPackage("com.rentflow..")
                .and()
                .doNotHaveSimpleName("ReservationApplication")
                .should()
                .resideInAnyPackage(
                        "..config..",
                        "..controller..",
                        "..converter..",
                        "..dto..",
                        "..model..",
                        "..repository..",
                        "..service..",
                        "..util..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void applicationPackagesAreFreeOfCycles() {
        slices().matching("com.rentflow.(*)..").should().beFreeOfCycles().check(PRODUCTION_CLASSES);
    }

    @Test
    void controllersNeverDependOnRepositories() {
        noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..repository..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void componentsUseTheirDefinedPackagesAndSuffixes() {
        classes()
                .that()
                .areAnnotatedWith(RestController.class)
                .should()
                .resideInAPackage("..controller..")
                .andShould()
                .haveSimpleNameEndingWith("Controller")
                .check(PRODUCTION_CLASSES);
        classes()
                .that()
                .areAnnotatedWith(RestControllerAdvice.class)
                .should()
                .resideInAPackage("..controller..")
                .check(PRODUCTION_CLASSES);
        classes()
                .that()
                .areAnnotatedWith(Service.class)
                .should()
                .resideInAPackage("..service..")
                .andShould()
                .haveSimpleNameEndingWith("Service")
                .check(PRODUCTION_CLASSES);
        classes()
                .that()
                .resideInAPackage("..converter..")
                .should()
                .haveSimpleNameEndingWith("Converter")
                .check(PRODUCTION_CLASSES);
        classes()
                .that()
                .resideInAPackage("..repository..")
                .and()
                .areInterfaces()
                .should()
                .haveSimpleNameEndingWith("Repository")
                .check(PRODUCTION_CLASSES);
        classes()
                .that()
                .areAnnotatedWith(Entity.class)
                .should()
                .resideInAPackage("..model..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void onlyControllersOwnMvcMappingAnnotations() {
        for (Class<? extends Annotation> annotation : MVC_MAPPING_ANNOTATIONS) {
            noClasses()
                    .that()
                    .resideOutsideOfPackage("..controller..")
                    .should()
                    .beAnnotatedWith(annotation)
                    .check(PRODUCTION_CLASSES);
            noMethods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideOutsideOfPackage("..controller..")
                    .should()
                    .beAnnotatedWith(annotation)
                    .check(PRODUCTION_CLASSES);
        }
    }

    @Test
    void applicationCodeDoesNotDependOnSpringSecurity() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework.security..")
                .check(PRODUCTION_CLASSES);
    }
}
