package org.broadinstitute.hellbender.tools.walkers.bqsr;

import org.broadinstitute.hellbender.CommandLineProgramTest;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.testutils.IntegrationTestSpec;
import org.broadinstitute.hellbender.utils.Utils;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Tests Analyze Covariates.
 * <p/>
 * Notice that since PDF report generated by R are different every-time this program
 * is executed their content won't be tested. It only will verify that file has a healthy size.
 *
 */
public final class AnalyzeCovariatesIntegrationTest extends CommandLineProgramTest{

    /**
     * Directory where the testdata is located.
     */
    private static final File TEST_DATA_DIR = new File(CommandLineProgramTest.getTestDataDir(),"AnalyzeCovariates");

    /**
     * File containing the before report for normal testing.
     */
    private static final File BEFORE_FILE = new File(TEST_DATA_DIR,"before.table.gz");

    /**
     * File containing the after report for normal testing.
     */
    private static final File AFTER_FILE = new File(TEST_DATA_DIR,"after.table.gz");


    /**
     * File containing the bqsr report for normal testing.
     */
    private static final File BQSR_FILE = new File(TEST_DATA_DIR,"bqsr.table.gz");


    /**
     * Test the content of the generated csv file.
     *
     * @throws java.io.IOException should never happen. It would be an indicator of a
     * problem with the testing environment.
     */
    @Test
    public void testCsvGeneration()
            throws IOException {

       final IntegrationTestSpec spec = new IntegrationTestSpec(
               buildCommandLine("%s",null,true,true,true),
               Collections.singletonList(new File(getTestDataDir(), "expected.AnalyzeCovariatesIntegrationTest.csv.gz").getAbsolutePath()));
        spec.executeTest("testCsvGeneration", this);
    }

    /**
     * Test the effect of changing some recalibration parameters.
     * @param afterFileName name of the alternative after recalibration file.
     * @param description describes what has been changed.
     * @throws java.io.IOException should never happen. It would be an
     *        indicator of a problem with the testing environment.
     */
    @Test(dataProvider="alternativeAfterFileProvider")
    public void testParameterChangeException(final String afterFileName,
            final String description)
            throws IOException {

        final File afterFile = new File(TEST_DATA_DIR,afterFileName);
        final IntegrationTestSpec spec = new IntegrationTestSpec(
                buildCommandLine(null,"%s",true,true,afterFile),
                1,UserException.IncompatibleRecalibrationTableParameters.class);
        spec.executeTest("testParameterChangeException - " + description, this);
    }


    /**
     * Test combinations of input and output inclusion exclusion of the command
     * line that cause an exception to be thrown.
     *
     * @param useCsvFile  whether to include the output csv file.
     * @param usePdfFile  whether to include the output pdf file.
     * @param useBQSRFile whether to include the -BQSR input file.
     * @param useBeforeFile whether to include the -before input file.
     * @param useAfterFile  whether to include the -after input file.
     * @throws java.io.IOException never thrown, unless there is a problem with the testing environment.
     */
    @Test(dataProvider="alternativeInOutAbsenceCombinations")
    public void testInOutAbsenceException(final boolean useCsvFile, final boolean usePdfFile,
            final boolean useBQSRFile, final boolean useBeforeFile, final boolean useAfterFile)
            throws IOException {
        final IntegrationTestSpec spec = new IntegrationTestSpec(buildCommandLine(useCsvFile,usePdfFile,
                useBQSRFile,useBeforeFile,useAfterFile),0,UserException.class);
        spec.executeTest("testInOutAbsencePresenceException", this);
    }

    /**
     * Test combinations of input and output inclusion exclusion of the
     * command line that won't cause an exception.
     *
     * @param useCsvFile  whether to include the output csv file.
     * @param usePdfFile  whether to include the output pdf file.
     * @param useBQSRFile whether to include the -BQSR input file.
     * @param useBeforeFile whether to include the -before input file.
     * @param useAfterFile  whether to include the -after input file.
     * @throws java.io.IOException never thrown, unless there is a problem with the testing environment.
     */
    @Test(groups = {"python"}, dataProvider="alternativeInOutAbsenceCombinations")
    public void testInOutAbsence(final boolean useCsvFile, final boolean usePdfFile,
            final boolean useBQSRFile, final boolean useBeforeFile, final boolean useAfterFile)
            throws IOException {
        final List<String> empty = Collections.emptyList();
        final IntegrationTestSpec spec = new IntegrationTestSpec(buildCommandLine(useCsvFile,usePdfFile,
                useBQSRFile,useBeforeFile,useAfterFile),empty);
        spec.executeTest("testInOutAbsencePresence", this);
    }

    @DataProvider
    public Iterator<Object[]> alternativeInOutAbsenceCombinations(Method m) {
        List<Object[]> result = new LinkedList<>();
        if (m.getName().endsWith("Exception")) {
            result.add(new Object[] { true, false, false, false ,false});
        } else {
            result.add(new Object[] { true, false, true, false, false });
            result.add(new Object[] { true, false, false, true, false });
            result.add(new Object[] { true, false, false, false, true });
            result.add(new Object[] { true, true, true, false, false });
            result.add(new Object[] { true, true, false, true, false });
            result.add(new Object[] { true, true, false, false, true });
        }
        return result.iterator();
    }

    /**
     * Provide recalibration parameter change data to relevant tests.
     * @param m target test method.
     * @return never <code>null</code>.
     */
    @DataProvider
    public Iterator<Object[]> alternativeAfterFileProvider (Method m) {
        final boolean expectsException = m.getName().endsWith("Exception");
        final List<Object[]> result = new LinkedList<>();
        for (final Object[] data : DIFFERENT_PARAMETERS_AFTER_FILES) {
           if (data[1].equals(expectsException)) {
               result.add(new Object[] { data[0], data[2] });
           }
        }
        return result.iterator();
    }

    /**
     * Triplets &lt; alfter-grp-file, whether it should fail, what is different &gt;
     */
    private final Object[][] DIFFERENT_PARAMETERS_AFTER_FILES = {
            {"after-noDp.table.gz",true, "Unset the default platform" },
            {"after-mcs4.table.gz", true, "Changed -mcs parameter from 2 to 4" }
    };

    /**
     * Build the AC command line given what combinations of input and output files should be included.
     *
     * @param useCsvFile  whether to include the output csv file.
     * @param usePdfFile  whether to include the output pdf file.
     * @param useBQSRFile whether to include the -BQSR input file.
     * @param useBeforeFile whether to include the -before input file.
     * @param useAfterFile  whether to include the -after input file.
     * @return never <code>null</code>.
     * @throws java.io.IOException never thrown, unless there is a problem with the testing environment.
     */
    private String buildCommandLine(final boolean useCsvFile, final boolean usePdfFile,
            final boolean useBQSRFile, final boolean useBeforeFile, final boolean useAfterFile)
            throws IOException {

        final File csvFile = useCsvFile ? createTempFile("ACTest",".csv") : null;
        final File pdfFile = usePdfFile ? createTempFile("ACTest",".pdf") : null;

        return buildCommandLine(csvFile == null ? null : csvFile.toString(),
                pdfFile == null ? null : pdfFile.toString(),
                useBQSRFile,useBeforeFile,useAfterFile);
    }

    /**
     * Build the AC command line given the output file names explicitly and what test input files to use.
     * <p/>
     *
     * @param csvFileName the csv output file, <code>null</code> if none should be provided.
     * @param pdfFileName the plots output file, <code>null</code> if none should be provided.
     * @param useBQSRFile whether to include the -BQSR input file.
     * @param useBeforeFile whether to include the -before input file.
     * @param useAfterFile  whether to include the -after input file.
     *
     * @return never <code>null</code>.
     */
    private String  buildCommandLine(final String csvFileName, final String pdfFileName, final boolean useBQSRFile,
                                    final boolean useBeforeFile, final boolean useAfterFile) {
        return buildCommandLine(csvFileName,pdfFileName,useBQSRFile ? BQSR_FILE : null,
                useBeforeFile ? BEFORE_FILE : null,
                useAfterFile ? AFTER_FILE : null);
    }

    /**
     * Build the AC command line given the output file names and the after file name explicitly and what other
     * test input files to use.
     * <p/>
     *
     * @param csvFileName the csv output file, <code>null</code> if none should be provided.
     * @param pdfFileName the plots output file, <code>null</code> if none should be provided.
     * @param useBQSRFile whether to include the -BQSR input file.
     * @param useBeforeFile whether to include the -before input file.
     * @param afterFile  the after input report file, <code>null</code> if none should be provided.
     *
     * @return never <code>null</code>.
     */
    private String buildCommandLine(final String csvFileName, final String pdfFileName, final boolean useBQSRFile,
                                    final boolean useBeforeFile, final File afterFile) {
        return buildCommandLine(csvFileName,pdfFileName,useBQSRFile ? BQSR_FILE : null,
                useBeforeFile ? BEFORE_FILE : null,
                afterFile);
    }

    /**
     * Build the AC command line given the output file names and the after file name explicitly and what other
     * test input files to use.
     * <p/>
     *
     * @param csvFileName the csv output file, <code>null</code> if none should be provided.
     * @param pdfFileName the plots output file, <code>null</code> if none should be provided.
     * @param bqsrFile the BQSR input report file, <code>null</code> if none should be provided.
     * @param beforeFile the before input report file, <code>null</code> if non should be provided.
     * @param afterFile  the after input report file, <code>null</code> if none should be provided.
     *
     * @return never <code>null</code>.
     */
    private String buildCommandLine(final String csvFileName, final String pdfFileName, final File bqsrFile,
        final File beforeFile, final File afterFile) {

        final List<String> args = new LinkedList<>();
        args.add("--" + AnalyzeCovariates.IGNORE_LMT_LONG_NAME);

        if (csvFileName != null) {
            args.add("-" + AnalyzeCovariates.CSV_ARG_SHORT_NAME);
            args.add("'" + csvFileName + "'");
        }
        if (pdfFileName != null) {
            args.add("-" + AnalyzeCovariates.PDF_ARG_SHORT_NAME);
            args.add("'" + pdfFileName + "'");
        }
        if (bqsrFile != null) {
            args.add("-" + StandardArgumentDefinitions.BQSR_TABLE_SHORT_NAME);
            args.add("'" + bqsrFile.getAbsoluteFile().toString() + "'");
        }
        if (beforeFile != null) {
            args.add("-" + AnalyzeCovariates.BEFORE_ARG_SHORT_NAME);
            args.add("'" + beforeFile.getAbsolutePath() + "'");
        }
        if (afterFile != null) {
            args.add("-" + AnalyzeCovariates.AFTER_ARG_SHORT_NAME);
            args.add("'" + afterFile.getAbsolutePath() + "'");
        }
        return Utils.join(" ", args);

    }
}
