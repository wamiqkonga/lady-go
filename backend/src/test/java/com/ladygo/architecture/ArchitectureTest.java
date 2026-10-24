package com.ladygo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Pure bytecode checks, no Spring context / no database - fast, always-on structural rules.
 *
 * <p>Adversarial review finding, CRITICAL: a status check written as
 * {@code status.ordinal() >= DRIVER_ACCEPTED.ordinal()} was meant to require "accepted or later", but
 * {@code CANCELLED} is the LAST value declared in {@code BookingStatus}, so that comparison actually
 * ADMITTED a cancelled booking too - an offered-but-never-accepted driver could read a customer's
 * exact pickup address for a ride the customer had already cancelled. {@code .ordinal()} silently
 * depends on enum DECLARATION ORDER, which is exactly the kind of thing nobody re-checks when a status
 * is inserted or reordered later. There is no legitimate use of it anywhere in this codebase's
 * production code (confirmed: every status/security decision is now written as an explicit
 * {@code EnumSet}/allow-list, see {@code BookingVisibilityPolicy}) - so it is banned outright, not just
 * discouraged, and this test is what makes that permanent rather than a one-time fix.
 */
@AnalyzeClasses(packages = "com.ladygo", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    /** {@code callMethod(Enum.class, "ordinal")} alone does NOT catch this: javac records the call
     *  site's owner as the enum's own type (e.g. {@code BookingStatus}), not {@code java.lang.Enum},
     *  even though {@code ordinal()} is only ever declared on {@code Enum} itself - confirmed by
     *  writing a deliberate violation and seeing that exact matcher miss it. Matching on the method
     *  name plus "owner is some enum type" is what actually catches every call site regardless of
     *  which enum it's invoked through. */
    private static final DescribedPredicate<JavaCall<?>> CALLS_ORDINAL = new DescribedPredicate<>("calls ordinal()") {
        @Override
        public boolean test(JavaCall<?> call) {
            return call.getTarget().getName().equals("ordinal")
                    && call.getTarget().getOwner().isAssignableTo(Enum.class);
        }
    };

    @ArchTest
    static final ArchRule no_ordinal_calls_in_production_code =
            noClasses().should().callMethodWhere(CALLS_ORDINAL)
                    .because("ordinal() depends on enum declaration order, which is too fragile a "
                            + "foundation for a status/security check - write an explicit EnumSet/allow-list "
                            + "instead (see BookingVisibilityPolicy for the pattern this fixed a real bug with)");
}
