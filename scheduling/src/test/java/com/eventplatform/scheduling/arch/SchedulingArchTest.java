package com.eventplatform.scheduling.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.ControllerAdvice;

@AnalyzeClasses(packages = "com.eventplatform.scheduling")
class SchedulingArchTest {

  @ArchTest
  static final ArchRule should_not_have_controller_advice =
      noClasses().should().beAnnotatedWith(ControllerAdvice.class);

  @ArchTest
  static final ArchRule should_not_depend_on_downstream_modules =
      noClasses()
          .that()
          .resideInAPackage("..scheduling..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..com.eventplatform.bookinginventory..",
              "..com.eventplatform.paymentsticketing..",
              "..com.eventplatform.promotions..",
              "..com.eventplatform.engagement..",
              "..com.eventplatform.admin..");
}
