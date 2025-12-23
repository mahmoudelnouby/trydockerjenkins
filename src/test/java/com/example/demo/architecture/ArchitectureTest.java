package com.example.demo.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

public class ArchitectureTest {

    private final JavaClasses importedClasses = new ClassFileImporter()
            .importPackages("com.dxc.ms_template");

    @Test
    void controllers_should_reside_in_controller_package() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Controller")
                .should().resideInAPackage("..controller..");
        rule.check(importedClasses);
    }

    @Test
    void services_should_reside_in_service_package() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Service")
                .should().resideInAPackage("..service..");
        rule.check(importedClasses);
    }

    @Test
    void repositories_should_reside_in_repository_package() {
        ArchRule rule = classes()
                .that().haveSimpleNameEndingWith("Repository")
                .should().resideInAPackage("..repository..");
        rule.check(importedClasses);
    }

    @Test
    void controllers_should_not_access_repositories_directly() {
        ArchRule rule = classes()
                .that().resideInAPackage("..controller..")
                .should().onlyAccessClassesThat()
                .resideOutsideOfPackage("..repository..");
        rule.check(importedClasses);
    }
}
