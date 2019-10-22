# Change Log

## Frequently Asked Questions

### With a version like 0.x, is Hail ready for use in publications?

Yes. The semantic versioning standard uses 0.x (development) versions to 
refer to software that is either "buggy" or "partial". While we don't view
Hail as particularly buggy (especially compared to one-off untested
scripts pervasive in bioinformatics!), Hail 0.2 is a partial realization
of a larger vision.

### What stability is guaranteed?

We do not intentionally break back-compatibility of interfaces or file 
formats. This means that a script developed to run on Hail 0.2.5 should
continue to work in every subsequent release within the 0.2 major version.
**The exception to this rule is experimental functionality, denoted as
such in the reference documentation, which may change at any time**.

Please note that **forward compatibility should not be expected, especially
relating to file formats**: this means that it may not be possible to use
an earlier version of Hail to read files written in a later version.  

---

## Version 0.2.25

Released 2019-10-14

### New features
- (hail#7240) Add interactive schema widget to `{MatrixTable, Table}.describe`. Use this by passing the argument `widget=True`.
- (hail#7250) `{Table, MatrixTable, Expression}.summarize()` now summarizes elements of collections (arrays, sets, dicts).
- (hail#7271) Improve `hl.plot.qq` by increasing point size, adding the unscaled p-value to hover data, and printing lambda-GC on the plot.
- (hail#7280) Add HTML output for `{Table, MatrixTable, Expression}.summarize()`.
- (hail#7294) Add HTML output for `hl.summarize_variants()`.

### Bug fixes
- (hail#7200) Fix VCF parsing with missingness inside arrays of floating-point values in the FORMAT field.
- (hail#7219) Fix crash due to invalid optimizer rule.

### Performance improvements
- (hail#7187) Dramatically improve performance of chained `BlockMatrix` multiplies without checkpoints in between.
- (hail#7195)(hail#7194) Improve performance of `group[_rows]_by` / `aggregate`.
- (hail#7201) Permit code generation of larger aggregation pipelines.

---

## Version 0.2.24

Released 2019-10-03

### `hailctl dataproc`
- (hail#7185) Resolve issue in dependencies that led to a Jupyter update breaking cluster creation.

### New features
- (hail#7071) Add `permit_shuffle` flag to `hl.{split_multi, split_multi_hts}` to allow processing of datasets with both multiallelics and duplciate loci.
- (hail#7121) Add `hl.contig_length` function.
- (hail#7130) Add `window` method on `LocusExpression`, which creates an interval around a locus.
- (hail#7172) Permit `hl.init(sc=sc)` with pip-installed packages, given the right configuration options.

### Bug fixes
- (hail#7070) Fix unintentionally strict type error in `MatrixTable.union_rows`.
- (hail#7170) Fix issues created downstream of `BlockMatrix.T`.
- (hail#7146) Fix bad handling of edge cases in `BlockMatrix.filter`.
- (hail#7182) Fix problem parsing VCFs where lines end in an INFO field of type flag.

---

## Version 0.2.23

Released 2019-09-23

### `hailctl dataproc`
- (hail#7087) Added back progress bar to notebooks, with links to the correct Spark UI url.
- (hail#7104) Increased disk requested when using `--vep` to address the "colony collapse" cluster error mode.

### Bug fixes
- (hail#7066) Fixed generated code when methods from multiple reference genomes appear together.
- (hail#7077) Fixed crash in `hl.agg.group_by`.
 
### New features
- (hail#7009) Introduced analysis pass in Python that mostly obviates the `hl.bind` and `hl.rbind` operators; idiomatic Python that generates Hail expressions will perform much better.
- (hail#7076) Improved memory management in generated code, add additional log statements about allocated memory to improve debugging.
- (hail#7085) Warn only once about schema mismatches during JSON import (used in VEP, Nirvana, and sometimes `import_table`.
- (hail#7106) `hl.agg.call_stats` can now accept a number of alleles for its `alleles` parameter, useful when dealing with biallelic calls without the alleles array at hand.
 
### Performance
- (hail#7086) Improved performance of JSON import.
- (hail#6981) Improved performance of Hail min/max/mean operators. Improved performance of `split_multi_hts` by an additional 33%.
- (hail#7082)(hail#7096)(hail#7098) Improved performance of large pipelines involving many `annotate` calls.

---

## Version 0.2.22

Released 2019-09-12

### New features
- (hail#7013) Added `contig_recoding` to `import_bed` and `import_locus_intervals`.

### Performance
- (hail#6969) Improved performance of `hl.agg.mean`, `hl.agg.stats`, and `hl.agg.corr`.
- (hail#6987) Improved performance of `import_matrix_table`.
- (hail#7033)(hail#7049) Various improvements leading to overall 10-15%
  improvement.

### `hailctl dataproc`
- (hail#7003) Pass through extra arguments for `hailctl dataproc list` and
  `hailctl dataproc stop`.

---

## Version 0.2.21

Released 2019-09-03

### Bug fixes
- (hail#6945) Fixed `expand_types` to preserve ordering by key, also affects
    `to_pandas` and `to_spark`.
- (hail#6958) Fixed stack overflow errors when counting the result of a `Table.union`.

### New features
- (hail#6856) Teach `hl.agg.counter` to weigh each value differently.
- (hail#6903) Teach `hl.range` to treat a single argument as `0..N`.
- (hail#6903) Teach `BlockMatrix` how to `checkpoint`.

### Performance
- (hail#6895) Improved performance of `hl.import_bgen(...).count()`.
- (hail#6948) Fixed performance bug in `BlockMatrix` filtering functions.
- (hail#6943) Improved scaling of `Table.union`.
- (hail#6980) Reduced compute time for `split_multi_hts` by as much as 40%.

### `hailctl dataproc`
- (hail#6904) Added `--dry-run` option to `submit`.
- (hail#6951) Fixed `--max-idle` and `--max-age` arguments to `start`.
- (hail#6919) Added `--update-hail-version` to `modify`.

---

## Version 0.2.20

Released 2019-08-19

### Critical memory management fix

- (hail#6824) Fixed memory management inside `annotate_cols` with
  aggregations. This was causing memory leaks and segfaults.

### Bug fixes
- (hail#6769) Fixed non-functional `hl.lambda_gc` method.
- (hail#6847) Fixed bug in handling of NaN in `hl.agg.min` and `hl.agg.max`.
  These will now properly ignore NaN (the intended semantics). Note that
  `hl.min` and `hl.max` propagate NaN; use `hl.nanmin` and  `hl.nanmax`
  to ignore NaN.

### New features
- (hail#6847) Added `hl.nanmin` and `hl.nanmax` functions. 

-----

## Version 0.2.19

Released 2019-08-01

### Critical performance bug fix

- (hail#6629) Fixed a critical performance bug introduced in (hail#6266).
  This bug led to long hang times when reading in Hail tables and matrix
  tables **written in version 0.2.18**.

### Bug fixes
- (hail#6757) Fixed correctness bug in optimizations applied to the
  combination of `Table.order_by` with `hl.desc` arguments and `show()`,
  leading to tables sorted in ascending, not descending order.
- (hail#6770) Fixed assertion error caused by `Table.expand_types()`,
  which was used by `Table.to_spark` and `Table.to_pandas`. 

### Performance Improvements

- (hail#6666) Slightly improve performance of `hl.pca` and 
  `hl.hwe_normalized_pca`.
- (hail#6669) Improve performance of `hl.split_multi` and 
  `hl.split_multi_hts`.
- (hail#6644) Optimize core code generation primitives, leading to
  across-the-board performance improvements.
- (hail#6775) Fixed a major performance problem related to reading block 
  matrices.
  
### `hailctl dataproc`

- (hail#6760) Fixed the address pointed at by `ui`  in `connect`, after
  Google changed proxy settings that rendered the UI URL incorrect. Also
  added new address `hist/spark-history`.

-----

## Version 0.2.18

Released 2019-07-12
    
### Critical performance bug fix

- (hail#6605) Resolved code generation issue leading a performance 
  regression of 1-3 orders of magnitude in Hail pipelines using
  constant strings or literals. This includes almost every pipeline!
  **This issue has exists in versions 0.2.15, 0.2.16, and 0.2.17, and
  any users on those versions should update as soon as possible.**
  
### Bug fixes

- (hail#6598) Fixed code generated by `MatrixTable.unfilter_entries` to
  improve performance. This will slightly improve the performance of
  `hwe_normalized_pca` and relatedness computation methods, which use
  `unfilter_entries` internally.

-----

## Version 0.2.17

Released 2019-07-10

### New features

- (hail#6349) Added `compression` parameter to `export_block_matrices`, which can
  be `'gz'` or `'bgz'`.
- (hail#6405) When a matrix table has string column-keys, `matrixtable.show` uses
  the column key as the column name.
- (hail#6345) Added an improved scan implementation, which reduces the memory
  load on master.
- (hail#6462) Added `export_bgen` method.
- (hail#6473) Improved performance of `hl.agg.array_sum` by about 50%.
- (hail#6498) Added method `hl.lambda_gc` to calculate the genomic control inflation factor.
- (hail#6456) Dramatically improved performance of pipelines containing long chains of calls to
  `Table.annotate`, or `MatrixTable` equivalents.
- (hail#6506) Improved the performance of the generated code for the `Table.annotate(**thing)`
  pattern.

### Bug fixes

- (hail#6404) Added `n_rows` and `n_cols` parameters to `Expression.show` for
  consistency with other `show` methods.
- (hail#6408)(hail#6419) Fixed an issue where the `filter_intervals` optimization
  could make scans return incorrect results.
- (hail#6459)(hail#6458) Fixed rare correctness bug in the `filter_intervals`
  optimization which could result too many rows being kept.
- (hail#6496) Fixed html output of `show` methods to truncate long field
  contents.
- (hail#6478) Fixed the broken documentation for the experimental `approx_cdf`
  and `approx_quantiles` aggregators.
- (hail#6504) Fix `Table.show` collecting data twice while running in Jupyter notebooks.
- (hail#6571) Fixed the message printed in `hl.concordance` to print the number of overlapping
  samples, not the full list of overlapping sample IDs.
- (hail#6583) Fixed `hl.plot.manhattan` for non-default reference genomes.

### Experimental

- (hail#6488) Exposed `table.multi_way_zip_join`. This takes a list of tables of
  identical types, and zips them together into one table.

-----

## Version 0.2.16

Released 2019-06-19

### `hailctl`

- (hail#6357) Accommodated Google Dataproc bug causing cluster creation failures. 
 
### Bug fixes

- (hail#6378) Fixed problem in how `entry_float_type` was being handled in `import_vcf`.

-----

## Version 0.2.15

Released 2019-06-14

After some infrastructural changes to our development process, we should be
getting back to frequent releases.

### `hailctl`

Starting in 0.2.15, `pip` installations of Hail come bundled with a command-
line tool, `hailctl`. This tool subsumes the functionality of `cloudtools`,
which is now deprecated. See the 
[release thread on the forum](https://discuss.hail.is/t/new-command-line-utility-hailctl/981)
for more information.

### New features

- (hail#5932)(hail#6115) `hl.import_bed` abd `hl.import_locus_intervals` now
  accept keyword arguments to pass through to `hl.import_table`, which is used
  internally. This permits parameters like `min_partitions` to be set.
- (hail#5980) Added `log` option to `hl.plot.histogram2d`.
- (hail#5937) Added `all_matches` parameter to `Table.index` and
  `MatrixTable.index_{rows, cols, entries}`, which produces an array of all
  rows in the indexed object matching the index key. This makes it possible to,
  for example, annotate all intervals overlapping a locus.
- (hail#5913) Added functionality that makes arrays of structs easier to work
  with.
- (hail#6089) Added HTML output to `Expression.show` when running in a notebook.
- (hail#6172) `hl.split_multi_hts` now uses the original `GQ` value if the `PL`
  is missing.
- (hail#6123) Added `hl.binary_search` to search sorted numeric arrays.
- (hail#6224) Moved implementation of `hl.concordance` from backend to Python.
  Performance directly from `read()` is slightly worse, but inside larger
  pipelines this function will be optimized much better than before, and it
  will benefit improvements to general infrastructure.
- (hail#6214) Updated Hail Python dependencies.
- (hail#5979) Added optimizer pass to rewrite filter expressions on keys as
  interval filters where possible, leading to massive speedups for point queries.
  See the [blog post](https://discuss.hail.is/t/new-optimizer-pass-that-extracts-point-queries-and-interval-filters/979)
  for examples.

### Bug fixes

- (hail#5895) Fixed crash caused by `-0.0` floating-point values in `hl.agg.hist`.
- (hail#6013) Turned off feature in HTSJDK that caused crashes in `hl.import_vcf`
  due to header fields being overwritten with different types, if the field had
  a different type than the type in the VCF 4.2 spec.
- (hail#6117) Fixed problem causing `Table.flatten()` to be quadratic in the size
  of the schema.
- (hail#6228)(hail#5993) Fixed `MatrixTable.union_rows()` to join distinct keys
  on the right, preventing an unintentional cartesian product.
- (hail#6235) Fixed an issue related to aggregation inside `MatrixTable.filter_cols`.
- (hail#6226) Restored lost behavior where `Table.show(x < 0)` shows the entire table.
- (hail#6267) Fixed cryptic crashes related to `hl.split_multi` and `MatrixTable.entries()`
  with duplicate row keys.

-----

## Version 0.2.14

Released 2019-04-24

A back-incompatible patch update to PySpark, 2.4.2, has broken fresh pip
installs of Hail 0.2.13. To fix this, either *downgrade* PySpark to 2.4.1 or
upgrade to the latest version of Hail.

### New features

- (hail#5915) Added `hl.cite_hail` and `hl.cite_hail_bibtex` functions to
  generate appropriate citations.
- (hail#5872) Fixed `hl.init` when the `idempotent` parameter is `True`.

-----

## Version 0.2.13

Released 2019-04-18

Hail is now using Spark 2.4.x by default. If you build hail from source, you
will need to acquire this version of Spark and update your build invocations
accordingly.

### New features

- (hail#5828) Remove dependency on htsjdk for VCF INFO parsing, enabling
  faster import of some VCFs.
- (hail#5860) Improve performance of some column annotation pipelines.
- (hail#5858) Add `unify` option to `Table.union` which allows unification of
  tables with different fields or field orderings.
- (hail#5799) `mt.entries()` is four times faster.
- (hail#5756) Hail now uses Spark 2.4.x by default.
- (hail#5677) `MatrixTable` now also supports `show`.
- (hail#5793)(hail#5701) Add `array.index(x)` which find the first index of
  `array` whose value is equal to `x`.
- (hail#5790) Add `array.head()` which returns the first element of the array,
  or missing if the array is empty.
- (hail#5690) Improve performance of `ld_matrix`.
- (hail#5743) `mt.compute_entry_filter_stats` computes statistics about the number
  of filtered entries in a matrix table.
- (hail#5758) failure to parse an interval will now produce a much more detailed
  error message.
- (hail#5723) `hl.import_matrix_table` can now import a matrix table with no
  columns.
- (hail#5724) `hl.rand_norm2d` samples from a two dimensional random normal.

### Bug fixes

- (hail#5885) Fix `Table.to_spark` in the presence of fields of tuples.
- (hail#5882)(hail#5886) Fix `BlockMatrix` conversion methods to correctly
  handle filtered entries.
- (hail#5884)(hail#4874) Fix longstanding crash when reading Hail data files
  under certain conditions.
- (hail#5855)(hail#5786) Fix `hl.mendel_errors` incorrectly reporting children counts in
  the presence of entry filtering.
- (hail#5830)(hail#5835) Fix Nirvana support
- (hail#5773) Fix `hl.sample_qc` to use correct number of total rows when
  calculating call rate.
- (hail#5763)(hail#5764) Fix `hl.agg.array_agg` to work inside
  `mt.annotate_rows` and similar functions.
- (hail#5770) Hail now uses the correct unicode string encoding which resolves a
  number of issues when a Table or MatrixTable has a key field containing
  unicode characters.
- (hail#5692) When `keyed` is `True`, `hl.maximal_independent_set` now does not
  produce duplicates.
- (hail#5725) Docs now consistently refer to `hl.agg` not `agg`.
- (hail#5730)(hail#5782) Taught `import_bgen` to optimize its `variants` argument.

### Experimental

- (hail#5732) The `hl.agg.approx_quantiles` aggregate computes an approximation
  of the quantiles of an expression.
- (hail#5693)(hail#5396) `Table._multi_way_zip_join` now correctly handles keys
  that have been truncated.

-----

## Version 0.2.12

Released 2019-03-28

### New features

- (hail#5614) Add support for multiple missing values in `hl.import_table`.
- (hail#5666) Produce HTML table output for `Table.show()` when running in Jupyter notebook.

### Bug fixes

- (hail#5603)(hail#5697) Fixed issue where `min_partitions` on `hl.import_table` was non-functional.
- (hail#5611) Fix `hl.nirvana` crash.

### Experimental

- (hail#5524) Add `summarize` functions to Table, MatrixTable, and Expression.
- (hail#5570) Add `hl.agg.approx_cdf` aggregator for approximate density calculation.
- (hail#5571) Add `log` parameter to `hl.plot.histogram`.
- (hail#5601) Add `hl.plot.joint_plot`, extend functionality of `hl.plot.scatter`.
- (hail#5608) Add LD score simulation framework.
- (hail#5628) Add `hl.experimental.full_outer_join_mt` for full outer joins on `MatrixTable`s.

-----

## Version 0.2.11

Released 2019-03-06

### New features

- (hail#5374) Add default arguments to `hl.add_sequence` for running on GCP.
- (hail#5481) Added `sample_cols` method to `MatrixTable`.
- (hail#5501) Exposed `MatrixTable.unfilter_entries`. See `filter_entries` documentation for more information.
- (hail#5480) Added `n_cols` argument to `MatrixTable.head`.
- (hail#5529) Added `Table.{semi_join, anti_join}` and `MatrixTable.{semi_join_rows, semi_join_cols, anti_join_rows, anti_join_cols}`.
- (hail#5528) Added `{MatrixTable, Table}.checkpoint` methods as wrappers around `write` / `read_{matrix_table, table}`.  

### Bug fixes

- (hail#5416) Resolved issue wherein VEP and certain regressions were recomputed on each use, rather than once.
- (hail#5419) Resolved issue with `import_vcf` `force_bgz` and file size checks.
- (hail#5427) Resolved issue with `Table.show` and dictionary field types.
- (hail#5468) Resolved ordering problem with `Expression.show` on key fields that are not the first key.
- (hail#5492) Fixed `hl.agg.collect` crashing when collecting `float32` values.
- (hail#5525) Fixed `hl.trio_matrix` crashing when `complete_trios` is `False`.

-----

## Version 0.2.10

Released 2019-02-15

### New features

- (hail#5272) Added a new 'delimiter' option to Table.export.
- (hail#5251) Add utility aliases to `hl.plot` for `output_notebook` and `show`.
- (hail#5249) Add `histogram2d` function to `hl.plot` module.
- (hail#5247) Expose `MatrixTable.localize_entries` method for converting to a Table with an entries array.
- (hail#5300) Add new `filter` and `find_replace` arguments to `hl.import_table` and `hl.import_vcf` to apply regex and substitutions to text input.

### Performance improvements

- (hail#5298) Reduce size of exported VCF files by exporting missing genotypes without trailing fields.

### Bug fixes

- (hail#5306) Fix `ReferenceGenome.add_sequence` causing a crash.
- (hail#5268) Fix `Table.export` writing a file called 'None' in the current directory.
- (hail#5265) Fix `hl.get_reference` raising an exception when called before `hl.init()`.
- (hail#5250) Fix crash in `pc_relate` when called on a MatrixTable field other than 'GT'.
- (hail#5278) Fix crash in `Table.order_by` when sorting by fields whose names are not valid Python identifiers.
- (hail#5294) Fix crash in `hl.trio_matrix` when sample IDs are missing.
- (hail#5295) Fix crash in `Table.index` related to key field incompatibilities.

-----

## Version 0.2.9

Released 2019-01-30

### New features

 - (hail#5149) Added bitwise transformation functions: `hl.bit_{and, or, xor, not, lshift, rshift}`.
 - (hail#5154) Added `hl.rbind` function, which is similar to `hl.bind` but expects a function as the last argument instead of the first.
 
### Performance improvements

 - (hail#5107) Hail's Python interface generates tighter intermediate code, which should result in moderate performance improvements in many pipelines.
 - (hail#5172) Fix unintentional performance deoptimization related to `Table.show` introduced in 0.2.8.
 - (hail#5078) Improve performance of `hl.ld_prune` by up to 30x.

### Bug fixes

 - (hail#5144) Fix crash caused by `hl.index_bgen` (since 0.2.7)
 - (hail#5177) Fix bug causing `Table.repartition(n, shuffle=True)` to fail to increase partitioning for unkeyed tables.
 - (hail#5173) Fix bug causing `Table.show` to throw an error when the table is empty (since 0.2.8).
 - (hail#5210) Fix bug causing `Table.show` to always print types, regardless of `types` argument (since 0.2.8).
 - (hail#5211) Fix bug causing `MatrixTable.make_table` to unintentionally discard non-key row fields (since 0.2.8).
 
-----

## Version 0.2.8

Released 2019-01-15

### New features

 - (hail#5072) Added multi-phenotype option to `hl.logistic_regression_rows`
 - (hail#5077) Added support for importing VCF floating-point FORMAT fields as `float32` as well as `float64`. 

### Performance improvements

 - (hail#5068) Improved optimization of `MatrixTable.count_cols`.
 - (hail#5131) Fixed performance bug related to `hl.literal` on large values with missingness

### Bug fixes

 - (hail#5088) Fixed name separator in `MatrixTable.make_table`.
 - (hail#5104) Fixed optimizer bug related to experimental functionality.
 - (hail#5122) Fixed error constructing `Table` or `MatrixTable` objects with fields with certain character patterns like `$`.

-----

## Version 0.2.7

Released 2019-01-03

### New features

 - (hail#5046)(experimental) Added option to BlockMatrix.export_rectangles to export as NumPy-compatible binary.

### Performance improvements

 - (hail#5050) Short-circuit iteration in `logistic_regression_rows` and `poisson_regression_rows` if NaNs appear.

-----

## Version 0.2.6

Released 2018-12-17

### New features

 - (hail#4962) Expanded comparison operators (`==`, `!=`, `<`, `<=`, `>`, `>=`) to support expressions of every type.
 - (hail#4927) Expanded functionality of `Table.order_by` to support ordering by arbitrary expressions, instead of just top-level fields.
 - (hail#4926) Expanded default GRCh38 contig recoding behavior in `import_plink`.
  
### Performance improvements

 - (hail#4952) Resolved lingering issues related to (hail#4909).

### Bug fixes

 - (hail#4941) Fixed variable scoping error in regression methods.
 - (hail#4857) Fixed bug in maximal_independent_set appearing when nodes were named something other than `i` and `j`.
 - (hail#4932) Fixed possible error in `export_plink` related to tolerance of writer process failure.
 - (hail#4920) Fixed bad error message in `Table.order_by`.
 
-----

## Version 0.2.5 

Released 2018-12-07

### New features

 - (hail#4845) The [or_error](https://hail.is/docs/0.2/functions/core.html#hail.expr.builders.CaseBuilder.or_error) method in `hl.case` and `hl.switch` statements now takes a string expression rather than a string literal, allowing more informative messages for errors and assertions.
 - (hail#4865) We use this new `or_error` functionality in methods that require biallelic variants to include an offending variant in the error message.
 - (hail#4820) Added [hl.reversed](https://hail.is/docs/0.2/functions/collections.html?highlight=reversed#hail.expr.functions.reversed) for reversing arrays and strings.
 - (hail#4895) Added `include_strand` option to the [hl.liftover](https://hail.is/docs/0.2/functions/genetics.html?highlight=liftover#hail.expr.functions.liftover) function.


### Performance improvements
 
 - (hail#4907)(hail#4911) Addressed one aspect of bad scaling in enormous literal values (triggered by a list of 300,000 sample IDs) related to logging.
 - (hail#4909)(hail#4914) Fixed a check in Table/MatrixTable initialization that scaled O(n^2) with the total number of fields.

### Bug fixes

 - (hail#4754)(hail#4799) Fixed optimizer assertion errors related to certain types of pipelines using ``group_rows_by``.
 - (hail#4888) Fixed assertion error in BlockMatrix.sum.
 - (hail#4871) Fixed possible error in locally sorting nested collections.
 - (hail#4889) Fixed break in compatibility with extremely old MatrixTable/Table files.
 - (hail#4527)(hail#4761) Fixed optimizer assertion error sometimes encountered with ``hl.split_multi[_hts]``.

-----

## Version 0.2.4: Beginning of history!

We didn't start manually curating information about user-facing changes until version 0.2.4.

The full commit history is available [here](https://github.com/hail-is/hail/commits/master).
