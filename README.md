# The Genome Analysis Toolkit

[![Build Status](https://travis-ci.org/dakl/gatk.svg?branch=master)](https://travis-ci.org/dakl/gatk)

This is my public version of the (MIT licensed) GATK source tree with added custom walkers.


--------


## VariantAnnotator2000

*Party like it's 1999 + 1.*

VariantAnnotator with saner behavior. Only supports `--dbsnp`, `--resource` and `--expression`. 

### TL;DR

```bash
java -jar GenomeAnalysisTK-Klevebring.jar -T VariantAnnotator2000 \
-V public/gatk-engine/src/test/resources/dakl/my.vcf \
-R ~/genome_files/human_g1k_v37_decoy.fasta \
--resource:clinvar public/gatk-engine/src/test/resources/dakl/cv.vcf \
--dbsnp public/gatk-engine/src/test/resources/dakl/db.vcf \
-E clinvar.CLNACC -E clinvar.CLNSIG \
-L public/gatk-engine/src/test/resources/dakl/my.vcf -o ~/tmp/test.vcf
```

### Parameters

```bash
 -V,--variant <variant>                       Input VCF file
 -resource,--resource <resource>              External resource VCF file
 -dbsnp,--dbsnp <dbsnp>                       dbSNP VCF
 -alwaysAppendDbsnpId,--alwaysAppendDbsnpId   Append the dbSNP ID even when the variant VCF already has the ID field 
                                              populated
 -o,--out <out>                               File to which variants should be written
 -E,--expression <expression>                 One or more specific expressions to apply to variant calls

```

This Walker borrows code from VariantAnnotator and VariantAnnotatorEngine in core GATK. It is modified for better behaviour in two ways: 

1. To annotate from a resource file, it requires chr, pos, ref and alt to be identical 
2. If an allele is present on multiple rows in an input resource file, VariantAnnotator2000 will concatenate the selected fields (with a pipe, `|`). The GATK VariantAnnotator walker (as of v3.3) uses a single row randomly. 

* Further details: http://gatkforums.broadinstitute.org/discussion/5125/variantannotator-and-multiple-records-in-resources#latest
* https://www.broadinstitute.org/gatk/gatkdocs/org_broadinstitute_gatk_tools_walkers_annotator_VariantAnnotator.php#--expression




--------




## SelectPathogenicVariants

Walker to select pathogenic variants from clinvar.

### TL;DR

To select variants from certain genes, run

```bash
java -jar ./public/external-example/target/external-example-1.0-SNAPSHOT.jar -T SelectPathogenicVariants \
-V public/testdata/dakl/clinvar_20140807.vcf -R ~/genome/human_g1k_v37_decoy.fasta -o ~/tmp/tmp.vcf -G BRCA1 -G BRCA2
```

To select pathogenic variants from all genes, run

```bash
java -jar ./public/external-example/target/external-example-1.0-SNAPSHOT.jar -T SelectPathogenicVariants \
-V public/testdata/dakl/clinvar_20140807.vcf -R ~/genome/human_g1k_v37_decoy.fasta -o ~/tmp/tmp.vcf -G BRCA1 -G BRCA2
```

### Parameters

Arguments for SelectPathogenicVariants:

```bash
 -V,--variant <variant>      Input VCF file
 -o,--out <out>              File to which variants should be written
 -G,--genes <genes>          String of gene names from which variants are selected. Multiple allowed. If not specified,
                             all genes are selected
 -GI,--GENEINFO <GENEINFO>   Name of the field containing gene info.
```



--------




## TelomereQuant

Walker to quantify the telomeric content in a sample. The walker calculates the following:

- The number of reads that has at least `NR` repeats of the telomere repeat sequence (TTAGGG or CCCTAA) in them ([ref][teloquant]).
- The total number of repeats seen, only counting repeats in reads that has `NR` x teloseq. Reads that have fewer than `NR` x teloseq are not counted to avoid false positives.
- Total bases sequenced

The walker allows for multiple samples to be given within a single BAM file, and will calculate these figures for each sample given (using read groups).

[teloquant]: http://genomebiology.com/2012/13/12/R113

### TL;DR

```bash
java -jar ./public/external-example/target/external-example-1.0-SNAPSHOT.jar -T TelomereQuant -R reference.fa -I mybam.bam -o outfile.txt
```

### Parameters

```bash
 -o,--out <out>                                An output file created by the walker.  Will overwrite contents if file exists
 -NR,--number_of_repeats <number_of_repeats>   Number of repeats of telomere sequence required for a read to be classified as telomeric. Default 4.
```

--------


## HeterozygoteConcordance

Walker to calculate the number of heterozygote SNPs in a VCF file that have read support in a BAM file.

I use this to verify the identity of a tumor and normal exome or panel pairs. Variants are called in the normal sample, and then used as input (`-V`) to the walker. 

**Note:** Adding the optional parameter `-L <variant>` leads to a massive speedup, since the backend GATK engine only processes the sites passed to `-L`, instead of looping through all sites in the reference. The file passed to `-L` should be the same as that passed to `-V`. Example: `-V normalvariants.vcf -L normalvariants.vcf`.

The output is a table with the total number of variants, total hz variants and number of hz variants that have read support in the BAM, broken down per sample in the BAM file. 

### TL;DR

```bash
java -jar ./public/external-example/target/external-example-1.0-SNAPSHOT.jar -T HeterozygoteConcordance -I tumor.bam -V normalvariants.vcf -L normalvariants.vcf -R reference.fa -sid VcfSampleToUse -o output.txt
```

### Parameters

Arguments for HeterozygoteConcordance:

```bash
 -I,--input_file <input_file>   Input file containing sequence data (SAM or BAM) (required)
 -sid,--VCFSample <VCFSample>   Sample ID from which to get HZ calls. Must be present in the VCF. (required)
 -V,--variant <variant>         Input VCF file (required)
 -L <variant>                   Only process variants in VCF file (gives major speedup)
 -o,--out <out>                 File to which output should be written
 -md,--mindepth <mindepth>      Minimum BAM depth of a position to be considered (default 30)
 -up,--upper <upper>            Upper limit over which hom alt GTs are called (default .9)
 -lo,--lower <lower>            Lower limit over which hom ref GTs are called (default .1)
```



--------






## StupidGenotyper

Walker to "genotype" given positions across multiple samples. Only bialleleic SNPs are considered, all other variants are ignored. The approach used to genotype is stupid: 

* If `DP` < `min_depth_to_genotype`, then no call
* If `FA` <= `min_hz_threshold`, call hom ref
* If `FA` >= `max_hz_threshold`, call hom alt
* Otherwise ( `DP` >= `min_depth_to_genotype` and `min_hz_threshold` <= `FA` < `max_hz_threshold`), call het

(`DP`, depth; `FA`, fraction of reads supporting ALT)

**Note:** Adding the optional parameter `-L <variant>` leads to a massive speedup, since the backend GATK engine only processes the sites passed to `-L`, instead of looping through all sites in the reference. The file passed to `-L` should be the same as that passed to `-V`. Example: `-V normalvariants.vcf -L normalvariants.vcf`.

StupidGenotyper is both `NanoSchedulable` and `TreeReducible`, so both `-nt INT` and `-nct INT` can be used to speed things up if needed. See [the GATK parallelism docs](http://gatkforums.broadinstitute.org/discussion/1975/recommendations-for-parallelizing-gatk-tools) for details on how those flags work. 

### Why would you want such a stupid genotyper?

This walker can for example be used to call variants in BAM files the positions probed by the Affy6 chip. The goal is to investigate any potential sample mixups in large groups of samples. Do not consider genotypes generated from this tool correct on a single level. 

### TL;DR

```bash
java -jar ./public/external-example/target/external-example-1.0-SNAPSHOT.jar -T StupidGenotyper -V affy6.vcf -L affy6.vcf -R reference.fa -I mybam.bam -I bysecondbam.bam -o output.vcf
```

### Parameters

Arguments for StupidGenotyper:

```bash
Arguments for StupidGenotyper:
 -V,--variant <variant>                   Input VCF file
 -L <variant>                             Only process variants in VCF file (gives major  -o,--out <out>                            File to which variants should be written
 -mindp,--min_depth_to_genotype <mindp>   Don't genotype under this coverage (default 20)
 -min,--min_hz_threshold <min>            Minimum ALT fraction to call het (default 0.05)
 -max,--max_hz_threshold <max>            Maximum ALT fraction to call het (default 0.95)
```



--------



## Compile me

You need maven to compile the GATK.

```bash
git clone https://github.com/dakl/gatk.git gatk-klevebring
cd gatk-klevebring
```

I usually skip compiling Queue:

```bash
mvn verify -P\!queue
```

The resulting jar file is `./public/external-example/target/external-example-1.0-SNAPSHOT.jar`

Give it a saner name:

```bash
cp ./public/external-example/target/external-example-1.0-SNAPSHOT.jar ./GenomeAnalysisTK-Klevebring.jar
```








## Not working as expected?
Please report any bugs in the issue tracker on this site.


## See also

See http://www.broadinstitute.org/gatk/
