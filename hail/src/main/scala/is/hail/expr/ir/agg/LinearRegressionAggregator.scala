package is.hail.expr.ir.agg

import breeze.linalg.{DenseMatrix, DenseVector, diag, inv}
import is.hail.annotations.{Region, RegionValueBuilder, StagedRegionValueBuilder, UnsafeRow}
import is.hail.asm4s._
import is.hail.expr.ir.{EmitFunctionBuilder, EmitCode}
import is.hail.expr.types.physical.{PArray, PFloat64, PInt32, PStruct, PTuple}
import is.hail.utils.FastIndexedSeq

object LinearRegressionAggregator extends StagedAggregator {
  type State = TypedRegionBackedAggState

  val vector = PArray(PFloat64(true), true)
  val scalar = PFloat64(true)
  val stateType: PTuple = PTuple(true, vector, vector, PInt32(true))
  val nrVec = PArray(PFloat64())
  def resultType = PStruct("xty" -> nrVec, "beta" -> nrVec, "diag_inv" -> nrVec, "beta0" -> nrVec)

  def createState(fb: EmitFunctionBuilder[_]): State =
    new TypedRegionBackedAggState(stateType, fb)

  def initOpF(state: State)(mb: MethodBuilder, k: Code[Int], k0: Code[Int]): Code[Unit] = Code(
    (k0 < 0 | k0 > k).mux(
      Code._fatal[Unit](const("linreg: `nested_dim` must be between 0 and the number (")
        .concat(k.toS)
        .concat(") of covariates, inclusive")),
      Code._empty),
    state.off := stateType.allocate(state.region),
    Region.storeAddress(
      stateType.fieldOffset(state.off, 0),
      vector.zeroes(mb, state.region, k)),
    Region.storeAddress(
      stateType.fieldOffset(state.off, 1),
      vector.zeroes(mb, state.region, k*k)),
    Region.storeInt(
      stateType.loadField(state.off, 2),
      k0)
  )

  def initOp(state: State, init: Array[EmitCode], dummy: Boolean): Code[Unit] = {
    val _initOpF = state.fb.newMethod[Int, Int, Unit]("linregInitOp")(initOpF(state))
    val Array(kt, k0t) = init
    (Code(kt.setup, kt.m) || Code(k0t.setup, k0t.m)).mux(
      Code._fatal[Unit]("linreg: init args may not be missing"),
      _initOpF(coerce[Int](kt.v), coerce[Int](k0t.v)))
  }

  def seqOpF(state: State)(mb: MethodBuilder, y: Code[Double], x: Code[Long]): Code[Unit] = {
    val k = mb.newLocal[Int]
    val i = mb.newLocal[Int]
    val j = mb.newLocal[Int]
    val sptr = mb.newLocal[Long]
    val xptr = mb.newLocal[Long]
    val xptr2 = mb.newLocal[Long]
    val xty = mb.newLocal[Long]
    val xtx = mb.newLocal[Long]

    val body = Code(FastIndexedSeq(
      xty := stateType.loadField(state.off, 0),
      xtx := stateType.loadField(state.off, 1),
      sptr := vector.firstElementOffset(xty, k),
      xptr := vector.firstElementOffset(x, k),
      k := vector.loadLength(xty),
      i := 0,
      Code.whileLoop(i < k, Code(
        Region.storeDouble(sptr, Region.loadDouble(sptr)
          + (Region.loadDouble(xptr) * y)),
        i := i + 1,
        sptr := sptr + scalar.byteSize,
        xptr := xptr + scalar.byteSize
      )),

      i := 0,
      sptr := vector.firstElementOffset(xtx),
      xptr := vector.firstElementOffset(x, k),
      Code.whileLoop(i < k, Code(
        j := 0,
        xptr2 := vector.firstElementOffset(x, k),
        Code.whileLoop(j < k, Code(
          Region.storeDouble(sptr, Region.loadDouble(sptr)
            + (Region.loadDouble(xptr) * Region.loadDouble(xptr2))),
          j += 1,
          sptr := sptr + scalar.byteSize,
          xptr2 := xptr2 + scalar.byteSize)),
        i += 1,
        xptr := xptr + scalar.byteSize))))

    nrVec.anyMissing(mb, x).mux(Code._empty, body)
  }

  def seqOp(state: State, seq: Array[EmitCode], dummy: Boolean): Code[Unit] = {
    val _seqOpF = state.fb.newMethod[Double, Long, Unit]("linregSeqOp")(seqOpF(state))
    val Array(y, x) = seq
    (Code(y.setup, y.m) || Code(x.setup, x.m)).mux(
      Code._empty,
      _seqOpF(coerce[Double](y.v), coerce[Long](x.v)))
  }

  def combOpF(state: State, other: State)(mb: MethodBuilder): Code[Unit] = {
    val n = mb.newLocal[Int]
    val i = mb.newLocal[Int]
    val sptr = mb.newLocal[Long]
    val optr = mb.newLocal[Long]
    val xty = mb.newLocal[Long]
    val xtx = mb.newLocal[Long]
    val oxty = mb.newLocal[Long]
    val oxtx = mb.newLocal[Long]

    Code(FastIndexedSeq(
      xty := stateType.loadField(state.off, 0),
      xtx := stateType.loadField(state.off, 1),
      oxty := stateType.loadField(other.off, 0),
      oxtx := stateType.loadField(other.off, 1),
      n := vector.loadLength(xty),
      i := 0,
      sptr := vector.firstElementOffset(xty, n),
      optr := vector.firstElementOffset(oxty, n),
      Code.whileLoop(i < n, Code(
        Region.storeDouble(sptr, Region.loadDouble(sptr) + Region.loadDouble(optr)),
        i := i + 1,
        sptr := sptr + scalar.byteSize,
        optr := optr + scalar.byteSize)),

      n := vector.loadLength(xtx),
      i := 0,
      sptr := vector.firstElementOffset(xtx, n),
      optr := vector.firstElementOffset(oxtx, n),
      Code.whileLoop(i < n, Code(
        Region.storeDouble(sptr, Region.loadDouble(sptr) + Region.loadDouble(optr)),
        i := i + 1,
        sptr := sptr + scalar.byteSize,
        optr := optr + scalar.byteSize))))
  }

  def combOp(state: State, other: State, dummy: Boolean): Code[Unit] =
    state.fb.newMethod[Unit]("linregCombOp")(combOpF(state, other))

  def computeResult(region: Region, xtyPtr: Long, xtxPtr: Long, k0: Int): Long = {
    val xty = DenseVector(UnsafeRow.readArray(vector, null, xtyPtr)
      .asInstanceOf[IndexedSeq[Double]].toArray[Double])
    val k = xty.length
    val xtx = DenseMatrix.create(k, k, UnsafeRow.readArray(vector, null, xtxPtr)
      .asInstanceOf[IndexedSeq[Double]].toArray[Double])

    val rvb = new RegionValueBuilder(region)
    rvb.start(resultType)
    rvb.startStruct()

    try {
      val b = xtx \ xty
      val diagInv = diag(inv(xtx))

      val xtx0 = xtx(0 until k0, 0 until k0)
      val xty0 = xty(0 until k0)
      val b0 = xtx0 \ xty0

      rvb.startArray(k)
      var i = 0
      while (i < k) {
        rvb.addDouble(xty(i))
        i += 1
      }
      rvb.endArray()

      rvb.startArray(k)
      i = 0
      while (i < k) {
        rvb.addDouble(b(i))
        i += 1
      }
      rvb.endArray()

      rvb.startArray(k)
      i = 0
      while (i < k) {
        rvb.addDouble(diagInv(i))
        i += 1
      }
      rvb.endArray()

      rvb.startArray(k0)
      i = 0
      while (i < k0) {
        rvb.addDouble(b0(i))
        i += 1
      }
      rvb.endArray()
    } catch {
      case _: breeze.linalg.MatrixSingularException |
           _: breeze.linalg.NotConvergedException =>
        rvb.setMissing()
        rvb.setMissing()
        rvb.setMissing()
        rvb.setMissing()
    }

    rvb.endStruct()
    rvb.end()
  }

  def result(state: State, srvb: StagedRegionValueBuilder, dummy: Boolean): Code[Unit] = {
    val res = state.fb.newField[Long]
    coerce[Unit](Code(
      res := Code.invokeScalaObject[Region, Long, Long, Int, Long](getClass, "computeResult",
        srvb.region,
        stateType.loadField(state.off, 0),
        stateType.loadField(state.off, 1),
        Region.loadInt(stateType.loadField(state.off, 2))),
      srvb.addIRIntermediate(resultType)(res)
    ))
  }
}
