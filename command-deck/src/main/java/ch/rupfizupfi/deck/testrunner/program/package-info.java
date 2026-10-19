/**
 * The test types as programs (OQ-64 step 7): each {@link ch.rupfizupfi.deck.testrunner.program.TestProgram}
 * declares its limits, its energize {@link ch.rupfizupfi.deck.testrunner.program.DriveSetup} and its
 * answers to the run loop's calls; the {@link ch.rupfizupfi.deck.testrunner.program.TestProgramRegistry}
 * maps every {@code TestType} to exactly one factory. A program acts on its run only through
 * {@code RunControl}, on the loop thread.
 */
package ch.rupfizupfi.deck.testrunner.program;
