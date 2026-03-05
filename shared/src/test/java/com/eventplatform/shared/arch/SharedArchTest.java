package com.eventplatform.shared.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.eventplatform.shared")
class SharedArchTest {

  @ArchTest
  static final ArchRule shared_should_not_depend_on_modules =
      noClasses()
          .that()
          .resideInAPackage("..shared..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..com.eventplatform.discoverycatalog..",
              "..com.eventplatform.scheduling..",
              "..com.eventplatform.identity..",
              "..com.eventplatform.bookinginventory..",
              "..com.eventplatform.paymentsticketing..",
              "..com.eventplatform.promotions..",
              "..com.eventplatform.engagement..",
              "..com.eventplatform.admin..");
}
