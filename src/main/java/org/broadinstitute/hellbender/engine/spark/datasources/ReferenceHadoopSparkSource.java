package org.broadinstitute.hellbender.engine.spark.datasources;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.reference.ReferenceSequence;
import htsjdk.samtools.reference.ReferenceSequenceFile;
import htsjdk.samtools.reference.ReferenceSequenceFileFactory;
import org.broadinstitute.hellbender.engine.GATKPathSpecifier;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.reference.ReferenceBases;

import java.io.Serializable;

/**
 * Class to load a reference sequence from a fasta file on HDFS.
 */
public class ReferenceHadoopSparkSource implements ReferenceSparkSource, Serializable {
    private static final long serialVersionUID = 1L;

    private final String referencePath;

    /**
     * @param referencePathSpecifier the path to the reference file on HDFS
     */
    public ReferenceHadoopSparkSource( final GATKPathSpecifier referencePathSpecifier) {
        // Ideally we would just cache the GATKPathSpecifier directly, which would simplify this code, but
        // ReferenceFileSparkSource objects are used as Spark broadcast variables, and caching GATKPathSpecifier
        // triggers a known issue with the Java 11 build. See https://issues.apache.org/jira/browse/SPARK-26963.
        this.referencePath = referencePathSpecifier.getRawInputString();
    }

    @Override
    public ReferenceBases getReferenceBases(final SimpleInterval interval) {
        ReferenceSequenceFile referenceSequenceFile = ReferenceSequenceFileFactory.getReferenceSequenceFile(IOUtils.getPath(referencePath));
        ReferenceSequence sequence = referenceSequenceFile.getSubsequenceAt(interval.getContig(), interval.getStart(), interval.getEnd());
        return new ReferenceBases(sequence.getBases(), interval);
    }

    @Override
    public SAMSequenceDictionary getReferenceSequenceDictionary(final SAMSequenceDictionary optReadSequenceDictionaryToMatch) {
        ReferenceSequenceFile referenceSequenceFile = ReferenceSequenceFileFactory.getReferenceSequenceFile(IOUtils.getPath(referencePath));
        return referenceSequenceFile.getSequenceDictionary();
    }

}
