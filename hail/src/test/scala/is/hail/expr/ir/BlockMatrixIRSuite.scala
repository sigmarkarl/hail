package is.hail.expr.ir

import breeze.linalg.{DenseMatrix => BDM}
import is.hail.HailSuite
import is.hail.expr.types.virtual.{TArray, TFloat64}
import is.hail.linalg.BlockMatrix
import is.hail.utils.FastIndexedSeq
import org.testng.annotations.Test

class BlockMatrixIRSuite extends HailSuite {

  val N_ROWS = 3
  val N_COLS = 3
  val shape: Array[Long] = Array[Long](N_ROWS, N_COLS)

  val negFours: BlockMatrix = BlockMatrix.fill(hc, N_ROWS, N_COLS,-4)
  val zeros: BlockMatrix    = BlockMatrix.fill(hc, N_ROWS, N_COLS, 0)
  val ones: BlockMatrix     = BlockMatrix.fill(hc, N_ROWS, N_COLS, 1)
  val twos: BlockMatrix     = BlockMatrix.fill(hc, N_ROWS, N_COLS, 2)
  val threes: BlockMatrix   = BlockMatrix.fill(hc, N_ROWS, N_COLS, 3)
  val fours: BlockMatrix    = BlockMatrix.fill(hc, N_ROWS, N_COLS, 4)

  def toBM(rows: Seq[Array[Double]]): BlockMatrix =
    toBM(rows, BlockMatrix.defaultBlockSize)

  def toBM(rows: Seq[Array[Double]], blockSize: Int): BlockMatrix = {
    val n = rows.length
    val m = if (n == 0) 0 else rows.head.length

    BlockMatrix.fromBreezeMatrix(sc, new BDM[Double](m, n, rows.flatten.toArray).t, blockSize)
  }

  def makeMatFromCol(vec: Seq[Double]): BlockMatrix = {
    toBM(vec.map(entry => Array(entry, entry, entry)))
  }

  def makeMatFromRow(vec: Seq[Double]): BlockMatrix = {
    toBM(Seq(vec.toArray, vec.toArray, vec.toArray))
  }

  def makeMap2(left: BlockMatrixIR, right: BlockMatrixIR,  op: BinaryOp, strategy: SparsityStrategy):
  BlockMatrixMap2 = {
    BlockMatrixMap2(left, right, "l", "r", ApplyBinaryPrimOp(op, Ref("l", TFloat64()), Ref("r", TFloat64())), strategy)
  }

  def assertBmEq(actual: BlockMatrix, expected: BlockMatrix) {
    assert(actual.toBreezeMatrix() == expected.toBreezeMatrix())
  }


  @Test def testBlockMatrixWriteRead() {
    val tempPath = tmpDir.createLocalTempFile()
    Interpret[Unit](ctx, BlockMatrixWrite(new BlockMatrixLiteral(ones),
      BlockMatrixNativeWriter(tempPath, false, false, false)))

    val actualMatrix = BlockMatrixRead(BlockMatrixNativeReader(tempPath)).execute(ctx)
    assertBmEq(actualMatrix, ones)
  }


  @Test def testBlockMatrixMap() {
    val sqrtFoursIR = BlockMatrixMap(new BlockMatrixLiteral(fours), "element", Apply("sqrt", FastIndexedSeq(Ref("element", TFloat64())), TFloat64()), false)
    val negFoursIR = BlockMatrixMap(new BlockMatrixLiteral(fours), "element", ApplyUnaryPrimOp(Negate(), Ref("element", TFloat64())), false)
    val logOnesIR = BlockMatrixMap(new BlockMatrixLiteral(ones), "element", Apply("log", FastIndexedSeq(Ref("element", TFloat64())), TFloat64()), true)
    val absNegFoursIR = BlockMatrixMap(new BlockMatrixLiteral(negFours), "element", Apply("abs", FastIndexedSeq(Ref("element", TFloat64())), TFloat64()), false)

    assertBmEq(sqrtFoursIR.execute(ctx), twos)
    assertBmEq(negFoursIR.execute(ctx), negFours)
    assertBmEq(logOnesIR.execute(ctx), zeros)
    assertBmEq(absNegFoursIR.execute(ctx), fours)
  }

  @Test def testBlockMatrixBroadcastValue_Scalars() {
    val broadcastTwo = BlockMatrixBroadcast(
      ValueToBlockMatrix(MakeArray(Seq[F64](F64(2)), TArray(TFloat64())), Array[Long](1, 1), 4096),
      FastIndexedSeq(), shape, 4096)

    val onesAddTwo = makeMap2(new BlockMatrixLiteral(ones), broadcastTwo, Add(), UnionBlocks)
    val threesSubTwo = makeMap2(new BlockMatrixLiteral(threes), broadcastTwo, Subtract(), UnionBlocks)
    val twosMulTwo = makeMap2(new BlockMatrixLiteral(twos), broadcastTwo, Multiply(), IntersectionBlocks)
    val foursDivTwo = makeMap2(new BlockMatrixLiteral(fours), broadcastTwo, FloatingPointDivide(), NeedsDense)

    assertBmEq(onesAddTwo.execute(ctx), threes)
    assertBmEq(threesSubTwo.execute(ctx), ones)
    assertBmEq(twosMulTwo.execute(ctx), fours)
    assertBmEq(foursDivTwo.execute(ctx), twos)
  }

  @Test def testBlockMatrixBroadcastValue_Vectors() {
    val vectorLiteral = MakeArray(Seq[F64](F64(1), F64(2), F64(3)), TArray(TFloat64()))

    val broadcastRowVector = BlockMatrixBroadcast(ValueToBlockMatrix(vectorLiteral, Array[Long](1, 3),
      0), FastIndexedSeq(1), shape, ones.blockSize)
    val broadcastColVector = BlockMatrixBroadcast(ValueToBlockMatrix(vectorLiteral, Array[Long](3, 1),
      0), FastIndexedSeq(0), shape, ones.blockSize)

    // Addition
    val actualOnesAddRowOnRight = makeMap2(new BlockMatrixLiteral(ones), broadcastRowVector, Add(), UnionBlocks)
    val actualOnesAddColOnRight = makeMap2(new BlockMatrixLiteral(ones), broadcastColVector, Add(), UnionBlocks)
    val actualOnesAddRowOnLeft  = makeMap2(broadcastRowVector, new BlockMatrixLiteral(ones), Add(), UnionBlocks)
    val actualOnesAddColOnLeft  = makeMap2(broadcastColVector, new BlockMatrixLiteral(ones), Add(), UnionBlocks)

    val expectedOnesAddRow = makeMatFromRow(Seq(2, 3, 4))
    val expectedOnesAddCol = makeMatFromCol(Seq(2, 3, 4))

    assertBmEq(actualOnesAddRowOnRight.execute(ctx), expectedOnesAddRow)
    assertBmEq(actualOnesAddColOnRight.execute(ctx), expectedOnesAddCol)
    assertBmEq(actualOnesAddRowOnLeft.execute(ctx),  expectedOnesAddRow)
    assertBmEq(actualOnesAddColOnLeft.execute(ctx),  expectedOnesAddCol)


    // Multiplication
    val actualOnesMulRowOnRight = makeMap2(new BlockMatrixLiteral(ones), broadcastRowVector, Multiply(), IntersectionBlocks)
    val actualOnesMulColOnRight = makeMap2(new BlockMatrixLiteral(ones), broadcastColVector, Multiply(), IntersectionBlocks)
    val actualOnesMulRowOnLeft  = makeMap2(broadcastRowVector, new BlockMatrixLiteral(ones), Multiply(), IntersectionBlocks)
    val actualOnesMulColOnLeft  = makeMap2(broadcastColVector, new BlockMatrixLiteral(ones), Multiply(), IntersectionBlocks)

    val expectedOnesMulRow = makeMatFromRow(Seq(1, 2, 3))
    val expectedOnesMulCol = makeMatFromCol(Seq(1, 2, 3))

    assertBmEq(actualOnesMulRowOnRight.execute(ctx), expectedOnesMulRow)
    assertBmEq(actualOnesMulColOnRight.execute(ctx), expectedOnesMulCol)
    assertBmEq(actualOnesMulRowOnLeft.execute(ctx),  expectedOnesMulRow)
    assertBmEq(actualOnesMulColOnLeft.execute(ctx),  expectedOnesMulCol)


    // Subtraction
    val actualOnesSubRowOnRight = makeMap2(new BlockMatrixLiteral(ones), broadcastRowVector, Subtract(), UnionBlocks)
    val actualOnesSubColOnRight = makeMap2(new BlockMatrixLiteral(ones), broadcastColVector, Subtract(), UnionBlocks)
    val actualOnesSubRowOnLeft  = makeMap2(broadcastRowVector, new BlockMatrixLiteral(ones), Subtract(), UnionBlocks)
    val actualOnesSubColOnLeft  = makeMap2(broadcastColVector, new BlockMatrixLiteral(ones), Subtract(), UnionBlocks)

    val expectedOnesSubRowRight = makeMatFromRow(Seq(0, -1, -2))
    val expectedOnesSubColRight = makeMatFromCol(Seq(0, -1, -2))
    val expectedOnesSubRowLeft = makeMatFromRow(Seq(0, 1, 2))
    val expectedOnesSubColLeft = makeMatFromCol(Seq(0, 1, 2))

    assertBmEq(actualOnesSubRowOnRight.execute(ctx), expectedOnesSubRowRight)
    assertBmEq(actualOnesSubColOnRight.execute(ctx), expectedOnesSubColRight)
    assertBmEq(actualOnesSubRowOnLeft.execute(ctx),  expectedOnesSubRowLeft)
    assertBmEq(actualOnesSubColOnLeft.execute(ctx),  expectedOnesSubColLeft)


    // Division
    val actualOnesDivRowOnRight = makeMap2(new BlockMatrixLiteral(ones), broadcastRowVector, FloatingPointDivide(), NeedsDense)
    val actualOnesDivColOnRight = makeMap2(new BlockMatrixLiteral(ones), broadcastColVector, FloatingPointDivide(), NeedsDense)
    val actualOnesDivRowOnLeft  = makeMap2(broadcastRowVector, new BlockMatrixLiteral(ones), FloatingPointDivide(), NeedsDense)
    val actualOnesDivColOnLeft  = makeMap2(broadcastColVector, new BlockMatrixLiteral(ones), FloatingPointDivide(), NeedsDense)

    val expectedOnesDivRowRight = makeMatFromRow(Seq(1, 1.0 / 2.0, 1.0 / 3.0))
    val expectedOnesDivColRight = makeMatFromCol(Seq(1, 1.0 / 2.0, 1.0 / 3.0))
    val expectedOnesDivRowLeft = makeMatFromRow(Seq(1, 2, 3))
    val expectedOnesDivColLeft = makeMatFromCol(Seq(1, 2, 3))

    assertBmEq(actualOnesDivRowOnRight.execute(ctx), expectedOnesDivRowRight)
    assertBmEq(actualOnesDivColOnRight.execute(ctx), expectedOnesDivColRight)
    assertBmEq(actualOnesDivRowOnLeft.execute(ctx),  expectedOnesDivRowLeft)
    assertBmEq(actualOnesDivColOnLeft.execute(ctx),  expectedOnesDivColLeft)
  }

  @Test def testBlockMatrixDot() {
    val dotTwosAndThrees = BlockMatrixDot(new BlockMatrixLiteral(twos), new BlockMatrixLiteral(threes))
    assertBmEq(dotTwosAndThrees.execute(ctx), BlockMatrix.fill(hc, 3, 3, 2 * 3 * 3))
  }
}
