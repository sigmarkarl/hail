package is.hail.expr.ir

import is.hail.HailSuite
import is.hail.expr.types.virtual.{TFloat64, TInt32, TTuple}
import org.apache.spark.sql.Row
import org.scalatest.testng.TestNGSuite
import org.testng.annotations.Test

class FoldConstantsSuite extends HailSuite {
  @Test def testRandomBlocksFolding() {
    val x = ApplySeeded("rand_norm", Seq(F64(0d), F64(0d)), 0L, TFloat64)
    assert(FoldConstants(ctx, x) == x)
  }

  @Test def testErrorCatching() {
    val ir = invoke("toInt32", TInt32, Str(""))
    assert(FoldConstants(ctx, ir) == ir)
  }
}
