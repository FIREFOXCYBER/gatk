package org.broadinstitute.hellbender.tools.walkers.mutect;

import htsjdk.samtools.*;
import htsjdk.samtools.reference.ReferenceSequenceFile;
import htsjdk.variant.variantcontext.VariantContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.broadinstitute.hellbender.cmdline.argumentcollections.ReferenceInputArgumentCollection;
import org.broadinstitute.hellbender.engine.AlignmentContext;
import org.broadinstitute.hellbender.engine.AssemblyRegion;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.tools.walkers.haplotypecaller.*;
import org.broadinstitute.hellbender.tools.walkers.haplotypecaller.readthreading.ReadThreadingAssembler;
import org.broadinstitute.hellbender.utils.*;
import org.broadinstitute.hellbender.utils.downsampling.DownsamplingMethod;
import org.broadinstitute.hellbender.utils.genotyper.AlleleLikelihoods;
import org.broadinstitute.hellbender.utils.genotyper.IndexedSampleList;
import org.broadinstitute.hellbender.utils.genotyper.SampleList;
import org.broadinstitute.hellbender.utils.haplotype.EventMap;
import org.broadinstitute.hellbender.utils.haplotype.Haplotype;
import org.broadinstitute.hellbender.utils.locusiterator.AlignmentStateMachine;
import org.broadinstitute.hellbender.utils.locusiterator.LocusIteratorByState;
import org.broadinstitute.hellbender.utils.pairhmm.PairHMM;
import org.broadinstitute.hellbender.utils.pileup.PileupElement;
import org.broadinstitute.hellbender.utils.pileup.ReadPileup;
import org.broadinstitute.hellbender.utils.read.*;
import org.broadinstitute.hellbender.utils.smithwaterman.SmithWatermanAligner;


import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.broadinstitute.hellbender.utils.gcs.BucketUtils.logger;

public class InferOriginalReadEngine implements DuplexConsensusCaller {
    private ReadLikelihoodCalculationEngine likelihoodCalculationEngine;
    private M2ArgumentCollection mtac = new M2ArgumentCollection();
    private HaplotypeCallerArgumentCollection hcac = new HaplotypeCallerArgumentCollection();
    private ReferenceInputArgumentCollection referenceArguments;
    private SAMFileHeader header;
    private HaplotypeCallerGenotypingEngine hcGenotypingEngine;
    private SAMFileGATKReadWriter outputWriter;

    private static final boolean ASSEMBLE_READS = false;
    public static final String CONSENSUS_READ_TAG = "CR";
    private static final int NUM_POSSIBLE_BASES = 4;
    private static final int DEFAULT_SCALING_FACTOR = 3;

    InferOriginalReadEngine(final SAMFileHeader header, final ReferenceInputArgumentCollection referenceArguments, final SAMFileGATKReadWriter outputWriter){
        mtac.likelihoodArgs.pairHMM = PairHMM.Implementation.LOGLESS_CACHING;
        likelihoodCalculationEngine = AssemblyBasedCallerUtils.createLikelihoodCalculationEngine(mtac.likelihoodArgs);
        this.header = header;
        this.referenceArguments = referenceArguments;
        hcac.standardArgs.genotypeArgs.samplePloidy = 1;
        this.outputWriter = outputWriter;
    }

    public void letsDoIt(final ArrayList<GATKRead> duplicateSet, final ReferenceContext referenceContext, final String umi){
        // Eventually must think about this;
        final Set<String> samplesList = ReadUtils.getSamplesFromHeader(header);
        final SampleList indexedSampleList = new IndexedSampleList(new ArrayList<>(samplesList));
        final int readLength = duplicateSet.get(0).getLength();

        // The default gap opening penalty of 45 is with respect to any position in the genome, which is too stringent in our case,
        // where we know that is at least one indel in the duplicate set
        final byte DEFAULT_GAP_OPENING_PENALTY = (byte) 15;
        final byte[] DEFAULT_GAP_OPENING_ARRAY = new byte[readLength];
        Arrays.fill(DEFAULT_GAP_OPENING_ARRAY, DEFAULT_GAP_OPENING_PENALTY);
        duplicateSet.forEach(r -> ReadUtils.setDeletionBaseQualities(r, DEFAULT_GAP_OPENING_ARRAY));
        duplicateSet.forEach(r -> ReadUtils.setInsertionBaseQualities(r, DEFAULT_GAP_OPENING_ARRAY));

        hcGenotypingEngine = new HaplotypeCallerGenotypingEngine(hcac, indexedSampleList, ! hcac.doNotRunPhysicalPhasing);

        // TreeMap, as opposed to a HashMap, ensures the iteration order guaranteed by the comparator of the key.
        final TreeMap<Pair<Strand, ReadNum>, List<GATKRead>> duplicateSetByStrandReadNum = duplicateSet.stream()
                .collect(Collectors.groupingBy(r -> getReadStrandReadNum(r), () -> new TreeMap<>(), Collectors.toList()));
        final TreeMap<Pair<Strand, ReadNum>, GATKRead> consensusReads = new TreeMap<>();

        for ( Map.Entry<Pair<Strand, ReadNum>, List<GATKRead>> readsById : duplicateSetByStrandReadNum.entrySet()) {
            final Strand strand = readsById.getKey().getLeft();
            final ReadNum readNum = readsById.getKey().getRight();
            final List<GATKRead> reads = readsById.getValue();
            final GATKRead consensusRead;
            reads.sort(new ReadCoordinateComparator(header));

            final boolean indelDetected = reads.stream().anyMatch(r -> CigarUtils.containsIndels(r.getCigar()));
            if (indelDetected) {
                /** {@link ReadThreadingAssembler.findBestPaths() } requires a graph. **/
                // PairHMM is different.
                // Learning of the PCR error likelihood should be done by deep learning. This should be the self-motivated project.
                // Find the consensus read
                final AssemblyResultSet assemblyResult;
                if (ASSEMBLE_READS) {
                    assemblyResult = doAssembly(reads, indexedSampleList);
                    // final SortedSet<VariantContext> set = assemblyResult.getVariationEvents(1);
                }

                // Can I just go from reads to haplotypes to events? Yes.
                final int regionStart = reads.stream().mapToInt(GATKRead::getStart).min().getAsInt();
                final int regionEnd = reads.stream().mapToInt(GATKRead::getEnd).max().getAsInt();
                final int REFERENCE_PADDING = 50; // We need padding because in the event of insertion we might need bases in the reference that falls outside of the tight window
                // must take the min of end of contig and regionEnd + padding
                final SimpleInterval refInterval = new SimpleInterval(referenceContext.getContig(), Math.max(regionStart - REFERENCE_PADDING, 0), regionEnd + REFERENCE_PADDING);
                final SimpleInterval justCurious = referenceContext.getInterval();
                // final Set<Haplotype> haplotypes = readsToHaplotypeSet(reads, refInterval);
                final List<Haplotype> haplotypes = readsToHaplotypeList(reads, refInterval);
                // Must create an empty assembly result set with haplotypes in order to call computeReadLikelihoods(), which
                // does not use any other information in the assembly result set.
                assemblyResult = new AssemblyResultSet();
                haplotypes.forEach(h -> assemblyResult.add(h));
                assemblyResult.setPaddedReferenceLoc(refInterval); // how does this compare to
                final ReferenceSequenceFile referenceReader = AssemblyBasedCallerUtils.createReferenceReader(Utils.nonNull(referenceArguments.getReferenceFileName()));
                final byte[] referenceBases = AssemblyRegion.getReference(referenceReader, 0, refInterval);
                assemblyResult.setFullReferenceWithPadding(referenceBases);
                // I'd have to write down ref, ref position, and what they are set to in the case of with assembly.
                // And then identify what kind of padding, refloc, etc., needs to be set to by hand.

                for (Haplotype h : haplotypes){
                    int d = 3;
                }

                final TreeSet<Integer> startPositions = EventMap.buildEventMapsForHaplotypes(haplotypes, referenceBases, refInterval, true, 1);
                int d = 3;

                final TreeMap<Map.Entry<LocationAndAlleles, List<Haplotype>>, Double> indelEvents = new TreeMap<>();
                for (int start : startPositions){
                    final List<VariantContext> eventsAtThisLoc = AssemblyBasedCallerUtils.getVariantContextsFromActiveHaplotypes(start, haplotypes, false);
                    final List<VariantContext> indelEventsAtThisLoc = eventsAtThisLoc.stream().filter(vc -> vc.isIndel()).collect(Collectors.toList());
                    if (indelEventsAtThisLoc.isEmpty()){
                        continue;
                    }

                    final Map<LocationAndAlleles, List<Haplotype>> countsByAllele = InferOriginalReadsUtils.countAllelesForAtThisLoc(start, haplotypes, false);
                    final Map.Entry<LocationAndAlleles, List<Haplotype>> identity = countsByAllele.entrySet().iterator().next(); // pick any entry
                    // In case of a tie one allele is chosen at random
                    final Map.Entry<LocationAndAlleles, List<Haplotype>> alleleWithHighestCount = countsByAllele.entrySet().stream().reduce(identity,
                            (entrySet1, entrySet2) -> entrySet1.getValue().size() > entrySet2.getValue().size() ? entrySet1 : entrySet2);
                    final double variance = InferOriginalReadsUtils.computeVarianceAroundMostCommon(countsByAllele, alleleWithHighestCount.getValue().size());
                    indelEvents.put(alleleWithHighestCount, variance);
                    int f = 3; // GOOD!
                }

                // START HERE, now that I have a list of indels, their positions, and the cigar, build a new cigar, and traverse reads according to this cigar
                final byte[] newInsertionQualities = new byte[readLength];
                final byte[] newDeletionQualities = new byte[readLength];
                Arrays.fill(newInsertionQualities, ReadUtils.DEFAULT_INSERTION_DELETION_QUAL);
                Arrays.fill(newDeletionQualities, ReadUtils.DEFAULT_INSERTION_DELETION_QUAL);
                final Optional<GATKRead> consensusHaplotype = findConsensusRead(indelEvents.keySet(), reads);


                boolean updatedInsertionQuality = false;
                boolean updatedDeletionQuality = false;

                // Find the representative read (should really build a map but that's OK)
                for (final Map.Entry<LocationAndAlleles, Double> indelEvent : indelEvents){
                    final int readStart = consensusRead.getStart();
                    final int offset = indelEvent.getStart() - readStart; // off by one?
                    if (indelEvent.isSimpleDeletion()){
                        newDeletionQualities[offset] = indelQuality;
                        updatedDeletionQuality = true;
                    } else {
                        // Must think about insertions more carefully, but OK for now
                        newInsertionQualities[offset] = indelQuality;
                        updatedInsertionQuality = true;
                    }
                }

                if (updatedDeletionQuality) consensusRead.setAttribute(ReadUtils.BQSR_BASE_DELETION_QUALITIES, newDeletionQualities);
                if (updatedInsertionQuality) consensusRead.setAttribute(ReadUtils.BQSR_BASE_INSERTION_QUALITIES, newInsertionQualities);
            } else {
                // No indels in the duplicate set. This isn't quite right, but it'll do for now.
                consensusRead = reads.get(0).deepCopy();
            }

            // Having identified the best haplotypes, give quals to each position of the consensus read
            final LocusIteratorByState libs = new LocusIteratorByState(reads.iterator(), DownsamplingMethod.NONE, false, samplesList,
                    header, true);
            int currentConsensusPositionInReference = consensusRead.getStart(); // This is the position in the reference

            AlignmentContext alignmentContext = libs.next();
            final byte[] newBaseQualities = new byte[readLength];
            final byte[] newBases = new byte[readLength];
            // We will step through the consensus read using the alignment state machine
            final AlignmentStateMachine asm = new AlignmentStateMachine(consensusRead);
            final CigarOperator currentCigarOperator = asm.stepForwardOnGenome();

            for (int i = 0; i < readLength; i++){
                if (alignmentContext.getStart() != currentConsensusPositionInReference){
                    logger.warn("AlignmentContext and read position in reference are out of sync: " + alignmentContext.getStart() + ", " + currentConsensusPositionInReference);
                }

                final CigarElement currentCigarElement = asm.getCurrentCigarElement();
                if (currentCigarElement.getOperator() == CigarOperator.I){
                    // Don't want to step forward on genome.
                    int d = 3;
                }

                if (currentCigarElement.getOperator() == CigarOperator.D){ // Something like this...
                    final int deletionLength = currentCigarElement.getLength();
                    libs.advanceToLocus(alignmentContext.getStart() + deletionLength, true); // is true OK here?
                }

                final ReadPileup pileup = alignmentContext.getBasePileup();
                final int[] baseCounts = pileup.getBaseCounts();
                final boolean disagreement = Arrays.stream(baseCounts).filter(x -> x > 0).count() > 1;
                if (disagreement){
                    int curiousToSee = 3;
                }
                final List<PileupElement> pes = pileup.getPileupElements();
                final long numDeletions = pes.stream().filter(pe -> pe.isDeletion()).count();
                final long numInsertionStart = pes.stream().filter(pe -> pe.isBeforeInsertion()).count();

                // We should skip computation if there's no collision e.g. base count is [0 0 15 0]
                final Pair<Integer, Double> result = getBaseQualityFromDependentEvidence(pileup.getBases(), pileup.getBaseQuals());
                newBases[i] = BaseUtils.baseIndexToSimpleBase(result.getLeft());
                newBaseQualities[i] = (byte) (-10 * result.getRight());

                if (libs.hasNext()){
                    alignmentContext = libs.next();
                } else {
                    Utils.validate(i == readLength - 1, "Bug: LocusIterator ran out of bases");
                }

                int d = 3; // ac.getStart() == 7_578_710
                currentConsensusPositionInReference++; // Unless it's an insertion base...not gonna think about it for now
            }

            int d = 3;
            consensusRead.setBases(newBases);
            consensusRead.setBaseQualities(newBaseQualities);
            consensusReads.put(new ImmutablePair<>(strand, readNum), consensusRead);
        }

        consensusReads.values().forEach(r -> outputWriter.addRead(r));
    }

    private Optional<GATKRead> findConsensusRead(final Set<Map.Entry<LocationAndAlleles, List<Haplotype>>> indelEvents, final List<GATKRead> reads) {
        Utils.validateArg(!indelEvents.isEmpty(), "indelEvents may not be empty");
        final List<Haplotype> readsThatContainAllConsensusEvents = indelEvents.iterator().next().getValue();
        for (Map.Entry<LocationAndAlleles, List<Haplotype>> event : indelEvents){
            // take the intersection of haplotypes across events
            readsThatContainAllConsensusEvents.retainAll(event.getValue());
        }

        if (readsThatContainAllConsensusEvents.isEmpty()){
            logger.warn("consensus read not found");
            return Optional.empty();
        }

        START HERE build a GATK Read from the haplotype!

        return Optional.of(readsThatContainAllConsensusEvents.get(0));
    }
    private Cigar getConsensusCigar(final List<GATKRead> reads, final TreeMap<LocationAndAlleles, Double> indelEvents) {
        final boolean reverseStrand = reads.get(0).isReverseStrand();
        if (reverseStrand){
            // build cigar from the largest coordinate, where they agree
            final List<Integer> endPositions = reads.stream().map(r -> r.getEnd()).distinct().collect(Collectors.toList());
            Utils.validate(endPositions.size() == 1, "The end positions of reverse reads in a duplicate set must agree");
            final int endPosition = endPositions.get(0);
            final LinkedList<CigarElement> cigarElements = new LinkedList<>();
            final TreeMap<LocationAndAlleles, Double> indelEventsInReverse = new TreeMap<>(Collections.reverseOrder());
            indelEventsInReverse.putAll(indelEvents);

            final LocationAndAlleles firstIndelLoc = indelEventsInReverse.pollFirstEntry().getKey(); // This is not quite right.
            Utils.validate(endPosition > firstIndelLoc, "end position must be greater");

            new CigarElement(endPosition - )

            // Is this worth it? Maybe just pick a representative read that contains these events.
            // could also use: CigarUtils.invertCigar
        }

        return new Cigar();
    }

    /** 12/4/19 AF-based filter, and unnecessarily complicated code that handles multi-ploidy case, etc,
     * opting for a simple implementation using the whole haplotype
     * **/
    public void likelihoodToGenotypesHC(final AlleleLikelihoods<GATKRead, Haplotype> readLikelihoods, final AssemblyResultSet assemblyResult){
        final CalledHaplotypes calledHaplotypes = hcGenotypingEngine.assignGenotypeLikelihoods(assemblyResult.getHaplotypeList(), readLikelihoods, new HashMap<>(),
                assemblyResult.getFullReferenceWithPadding(), // ts: ref bases. Where should it start/end?
                assemblyResult.getPaddedReferenceLoc(), // ts: what about reference loc?
                assemblyResult.getRegionForGenotyping().getSpan(),
                null, new ArrayList<>(), false, 1, header, false);
        // Genotypes are empty. How can I go from calls to genotype likelihoods?
    }

    // TODO: add unit test
    public Set<Haplotype> readsToHaplotypeSet(final List<GATKRead> reads, final SimpleInterval paddedRefInterval){
        final Set<Haplotype> haplotypes = new TreeSet<>();
        for (final GATKRead read : reads){
            final Haplotype hap = new Haplotype(read.getBases(), new SimpleInterval(read.getContig(), read.getStart(), read.getEnd()));
            hap.setCigar(read.getCigar());
            hap.setAlignmentStartHapwrtRef(read.getStart() - paddedRefInterval.getStart());
            haplotypes.add(hap);
        }
        return haplotypes;
    }

    // TODO: add unit test
    public List<Haplotype> readsToHaplotypeList(final List<GATKRead> reads, final SimpleInterval paddedRefInterval){
        final List<Haplotype> haplotypes = new ArrayList<>();
        for (final GATKRead read : reads){
            final Haplotype hap = new Haplotype(read.getBases(), new SimpleInterval(read.getContig(), read.getStart(), read.getEnd()));
            hap.setCigar(read.getCigar());
            hap.setAlignmentStartHapwrtRef(read.getStart() - paddedRefInterval.getStart());
            haplotypes.add(hap);
        }
        return haplotypes;
    }

    private AssemblyResultSet doAssembly(final List<GATKRead> reads, final SampleList indexedSampleList){
        final int maxDeletionLength = 100;
        final int start = reads.stream().mapToInt(GATKRead::getStart).min().getAsInt();
        final int end = reads.stream().mapToInt(GATKRead::getEnd).max().getAsInt();
        final SimpleInterval interval = new SimpleInterval(reads.get(0).getContig(), start, end); // is this wrong? should I get the min of the start positions?
        final AssemblyRegion assemblyRegion = new AssemblyRegion(interval, 0, header);
        assemblyRegion.addAll(reads);
        final ReferenceSequenceFile referenceReader = AssemblyBasedCallerUtils.createReferenceReader(Utils.nonNull(referenceArguments.getReferenceFileName()));
        final M2ArgumentCollection mtac = new M2ArgumentCollection(); // AssemblyBasedCallerArgumentCollect suffices, too.
        final ReadThreadingAssembler assemblyEngine = mtac.createReadThreadingAssembler();
        final SmithWatermanAligner aligner = SmithWatermanAligner.getAligner(mtac.smithWatermanImplementation);

        final AssemblyResultSet assemblyResult = AssemblyBasedCallerUtils.assembleReads(assemblyRegion, Collections.emptyList(),
                mtac, header, indexedSampleList, logger, referenceReader, assemblyEngine, aligner, false);
        return assemblyResult;
    }

    private void getEventsWithinDuplciateSetSketch(){
        // EventMap.buildEventMapsForHaplotypes(new ArrayList<>(haplotypes), referenceContext.getBases(), referenceContext.getInterval(), false, 1);
        // final SortedSet<VariantContext> events = AssemblyResultSet.getAllVariantContexts(new ArrayList<>(haplotypes));
        // final List<VariantContext> vc = AssemblyBasedCallerUtils.getVariantContextsFromActiveHaplotypes(); // ts: this is events at a particular locus
    }

    // Placeholder for PCR error
    final double PCR_ERROR_RATE = 1e-4;

    /**
     * @return The index of the consensus base and the associated error probability
     */
    public Pair<Integer, Double> getBaseQualityFromDependentEvidence(final byte[] basesAtLocus, final byte[] quals){
        Utils.validateArg(basesAtLocus.length == quals.length, "");

        final double[] log10Likelihoods = new double[NUM_POSSIBLE_BASES];

        for (int b = 0; b < NUM_POSSIBLE_BASES; b++){
            for (int i = 0; i < basesAtLocus.length; i++){
                if (BaseUtils.simpleBaseToBaseIndex(basesAtLocus[i]) == b){ // how does bases work....
                    log10Likelihoods[b] += QualityUtils.qualToProbLog10(quals[i]);
                } else {
                    log10Likelihoods[b] += - MathUtils.log10(3) + QualityUtils.qualToErrorProbLog10(quals[i]);
                }
            }
        }

        /** Question: are there advantages in using log for values close to 1? **/
        final double logNormalization = MathUtils.log10sumLog10(log10Likelihoods);
        final double[] log10NormalizedPosterior = Arrays.stream(log10Likelihoods).map(l -> l - logNormalization).toArray();
        final int indexOfConsensusBase = MathUtils.maxElementIndex(log10NormalizedPosterior);
        final double log10PosteriorIndependentErrorProb = IntStream.range(0, NUM_POSSIBLE_BASES).filter(i -> i != indexOfConsensusBase)
                .mapToDouble(i -> log10NormalizedPosterior[i]).sum();
        final double log10PcrError = getLog10PCRErrorProb();

        return new ImmutablePair<>(indexOfConsensusBase, MathUtils.log10sumLog10(new double[]{ log10PosteriorIndependentErrorProb, log10PcrError }));
    }

    private double getLog10PCRErrorProb(){
        // This is where I use the Weiss-model
        return -5.0;
    }

    private static Pair<Strand, ReadNum> getReadStrandReadNum(final GATKRead read){
        final Strand strand = ReadUtils.isF1R2(read) ? Strand.TOP : Strand.BOTTOM;
        final ReadNum readNum = read.isFirstOfPair() ? ReadNum.READ_ONE : ReadNum.READ_TWO;
        return new ImmutablePair<>(strand, readNum);
    }

    enum Strand {
        TOP, BOTTOM
    }

    enum ReadNum {
        READ_ONE, READ_TWO
    }
    
    
}
