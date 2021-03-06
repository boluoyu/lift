package opencl.generator.stencil.acoustic

import ir.ast._
import ir.{ArrayType, TupleType}
import lift.arithmetic.SizeVar
import opencl.executor.{Execute, Executor}
import opencl.ir._
import opencl.ir.pattern._
import org.junit.Assert._
import org.junit.{AfterClass, BeforeClass, Ignore, Test}

import scala.language.implicitConversions

object TestAcousticStencils {
  @BeforeClass def before(): Unit = {
    Executor.loadLibrary()
    println("Initialize the executor")
    Executor.init()
  }

  @AfterClass def after(): Unit = {
    println("Shutdown the executor")
    Executor.shutdown()
  }
}

class TestAcousticStencils {

  /* globals */
  val printOutput = false
  val slidesize = 3;
  val slidestep = 1;

  val dim = 8;
  val size = dim - 2;
  val delta = 0.2f

  val iter = 5



  val asymDimX2 = 6
  val asymDimY2 = 14

  /* helper functions */

  def print2DArray[T](input: Array[Array[T]]) = {
    println(input.deep.mkString("\n"))
  }

  def print1DArray[T](input: Array[T]) = {
    println(input.mkString(","))
  }

  def print1DArrayAs2DArray[T](input: Array[T], dimX: Int) {
    var count = 1
    println()
    input.foreach(x => if (count % dimX > 0) {
      print(x + " ");
      count += 1
    } else {
      println(x + " ");
      count += 1
    })
    println()
  }

  def printOriginalAndOutput[T](original: Array[Array[T]], output: Array[T], dimX: Int): Unit = {
    println("ORIGINAL:")
    print2DArray(original)
    println("*********************")
    println("OUTPUT:")
    print1DArrayAs2DArray(output, dimX)
  }


  val getFirstTuple = UserFun("getFirstTuple", "x", "{return x._0;}", TupleType(Float, Float), Float) // dud helper

  val getSecondTuple = UserFun("getSecondTuple", "x", "{return x._1;}", TupleType(Float, Float), Float) // dud helper

  /** ** Why doesn't this work?? !!!! *****/
  /*
    def createFakePadding[T](input: Array[Array[T]], padSize: Int, padValue: T): Array[Array[T]] = {

      val padLR = Array.fill(1)(padValue)
      val toppad = Array.fill(1)(Array.fill(padSize)(padValue))
      val output = input.map(i => padLR ++ i ++ padLR)
      toppad ++ output ++ toppad

    }
  */

  /* only one (value) layer of padding around 2D matrix */
  def createFakePaddingFloat(input: Array[Array[Float]], padValue: Float): Array[Array[Float]] = {

    val padSize = input(0).length
    val actualSize = padSize+2
    val padLR = Array.fill(1)(padValue)
    val toppad = Array.fill(1)(Array.fill(actualSize)(padValue))
    val output = input.map(i => padLR ++ i ++ padLR)
    toppad ++ output ++ toppad

  }

  def createFakePaddingInt(input: Array[Array[Int]], padValue: Int): Array[Array[Int]] = {

    val padSize = input(0).length
    val actualSize = padSize+2
    val padLR = Array.fill(1)(padValue)
    val toppad = Array.fill(1)(Array.fill(actualSize)(padValue))
    val output = input.map(i => padLR ++ i ++ padLR)
    toppad ++ output ++ toppad

  }

  /* Could refactor this to use createFakePaddingFloat */
  def createDataFloat(sizeX: Int, sizeY: Int) = {

    val dim = sizeX+2
    val filling = Array.tabulate(sizeX,sizeY) { (i,j) => (j + 1).toFloat }
    createFakePaddingFloat(filling,0.0f)
  }

  /* these helper functions do not work, but it would be nice if they did! */

  def map2D(f: Lambda1): FunDecl = {
    fun(x => x :>> MapSeq(fun(row => row :>> MapSeq(f))))
  }

  def reduce2D(f: Lambda2, init: Expr): FunDecl = {
    fun(x => x :>> MapSeq(fun(row => row :>> ReduceSeq(f, init))) :>> Transpose()
      :>> MapSeq(fun(n => n :>> ReduceSeq(f, init))) :>> Join())
  }

  val zip2D = fun((A, B) =>
    Map(\(tuple => Zip(tuple._0, tuple._1))) $ Zip(A, B)
  )

  /* shared data */
  val weights9 = Array.fill(9)(1).map(_.toFloat)


  val weightsArr = Array(
    0.0f, 1.0f, 0.0f,
    1.0f, 0.0f, 1.0f,
    0.0f, 1.0f, 0.0f)

  val weightsMiddleArr = Array(
    0.0f, 0.0f, 0.0f,
    0.0f, 1.0f, 0.0f,
    0.0f, 0.0f, 0.0f)


  val weights = Array(
    Array(0.0f, 1.0f, 0.0f),
    Array(1.0f, 0.0f, 1.0f),
    Array(0.0f, 1.0f, 0.0f))

  val weightsMiddle = Array(
    Array(0.0f, 0.0f, 0.0f),
    Array(0.0f, 1.0f, 0.0f),
    Array(0.0f, 0.0f, 0.0f))

  val stencilarr = createDataFloat(size,size)
  val stencilarrsame = createDataFloat(size,size)
  val stencilarrCopy = stencilarr.map(x => x.map(y => y * 2.0f))

  @Test
  def testStencil2DSimple(): Unit = {

    /* u[cp] = S */

    val compareData = Array(3.0f, 6.0f, 9.0f, 12.0f, 15.0f, 11.0f,
      4.0f, 8.0f, 12.0f, 16.0f, 20.0f, 17.0f,
      4.0f, 8.0f, 12.0f, 16.0f, 20.0f, 17.0f,
      4.0f, 8.0f, 12.0f, 16.0f, 20.0f, 17.0f,
      4.0f, 8.0f, 12.0f, 16.0f, 20.0f, 17.0f,
      3.0f, 6.0f, 9.0f, 12.0f, 15.0f, 11.0f
    )

    // JUST CREATES THE GROUPS !!
    /*     val lambda = fun(
          ArrayType(ArrayType(Float, SizeVar("M")), SizeVar("N")),
          (domain) => {
            MapGlb(1)(
              MapGlb(0)(fun(neighbours =>
                MapSeqOrMapSeqUnroll(MapSeqOrMapSeqUnroll(id)) $ neighbours
              ))
            ) o Slide2D(slidesize, slidestep) $ domain
          }
        )*/

    val lambda = fun(
      ArrayType(ArrayType(Float, SizeVar("M")), SizeVar("N")),
      (mat) => {
        MapGlb(1)(
          MapGlb(0)(fun(neighbours => {
            toGlobal(MapSeqUnroll(id)) o
              ReduceSeq(add, 0.0f) o Join() $ neighbours
          }))
        ) o Slide2D(slidesize, slidestep) $ mat
      })

    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, SizeVar("M")), SizeVar("N")),
      ArrayType(Float, weightsArr.length),
      (mat, weights) => {
        MapGlb(1)(
          MapGlb(0)(fun(neighbours => {
            toGlobal(MapSeqUnroll(id)) o
              ReduceSeqUnroll(fun((acc, pair) => {
                // Where does "acc" come from ? !!!!
                val pixel = Get(pair, 0)
                val weight = Get(pair, 1)
                multAndSumUp.apply(acc, pixel, weight)
              }), 0.0f) $ Zip(Join() $ neighbours, weights)
          }))
        ) o Slide2D(slidesize, slidestep) $ mat
      })

    val (output: Array[Float], runtime) = Execute(stencilarr.length, stencilarr.length)(lambdaNeigh, stencilarr, weightsArr)

    if (printOutput) printOriginalAndOutput(stencilarr, output, size)

    assertArrayEquals(compareData, output, delta)
    //println(ompile(lambda))

  }

  @Test
  def testStencil2DSimpleTimesConstant(): Unit = {

    val compareData = Array(
      6.0f, 12.0f, 18.0f, 24.0f, 30.0f, 22.0f,
      8.0f, 16.0f, 24.0f, 32.0f, 40.0f, 34.0f,
      8.0f, 16.0f, 24.0f, 32.0f, 40.0f, 34.0f,
      8.0f, 16.0f, 24.0f, 32.0f, 40.0f, 34.0f,
      8.0f, 16.0f, 24.0f, 32.0f, 40.0f, 34.0f,
      6.0f, 12.0f, 18.0f, 24.0f, 30.0f, 22.0f
    )

    /* cp => index
       S => stencil sum
      u[cp] = S*l2  */

    val constant = 2.0f

    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(Float, weightsArr.length),
      (mat, weights) => {
        MapGlb(1)(
          MapGlb(0)(fun(neighbours => {
            toGlobal(MapSeqUnroll(id)) o
              ReduceSeqUnroll(mult, constant) o
              ReduceSeqUnroll(fun((acc, pair) => {
                val pixel = Get(pair, 0)
                val weight = Get(pair, 1)
                multAndSumUp.apply(acc, pixel, weight)
              }), 0.0f) $ Zip(Join() $ neighbours, weights)
          }))
        ) o Slide2D(slidesize, slidestep) $ mat
      })

    val (output: Array[Float], runtime) = Execute(stencilarr.length, stencilarr.length)(lambdaNeigh, stencilarr, weightsArr)

    if (printOutput) printOriginalAndOutput(stencilarr, output, size)

    assertArrayEquals(compareData, output, delta)

  }


  @Test
  def testStencil2DSimpleTimesConstantPlusSelf(): Unit = {

    val compareData = Array(
      18.0f, 36.0f, 54.0f, 72.0f, 90.0f, 66.0f,
      24.0f, 48.0f, 72.0f, 96.0f, 120.0f, 102.0f,
      24.0f, 48.0f, 72.0f, 96.0f, 120.0f, 102.0f,
      24.0f, 48.0f, 72.0f, 96.0f, 120.0f, 102.0f,
      24.0f, 48.0f, 72.0f, 96.0f, 120.0f, 102.0f,
      18.0f, 36.0f, 54.0f, 72.0f, 90.0f, 66.0f
    )

    /* u[cp] = S*l2 + u1[cp] */

    val add2 = UserFun("add2", Array("x", "y"), "{ return y+y; }", Seq(Float, Float), Float).
      setScalaFun(xs => xs.head.asInstanceOf[Float] + xs(1).asInstanceOf[Float])

    val constant = 3.0f

    val timesConstantPlusSelf = UserFun("timesConstantPlusSelf", Array("x", "y"), "{ return x + x; }", Seq(Float, Float), Float)

    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, SizeVar("M")), SizeVar("N")),
      ArrayType(Float, weightsArr.length),
      (mat, weights) => {
        MapGlb(1)(
          MapGlb(0)(fun(neighbours => {
            toGlobal(MapSeq(id)) o
              MapSeq(fun(x => add(x, x))) o
              MapSeq(fun(x => mult(x, constant))) o
              ReduceSeq(fun((acc, pair) => {
                val pixel = Get(pair, 0)
                val weight = Get(pair, 1)
                multAndSumUp.apply(acc, pixel, weight)
              }), 0.0f) $ Zip(Join() $ neighbours, weights)
          }))
        ) o Slide2D(slidesize, slidestep) $ mat
      })

    val (output: Array[Float], runtime) = Execute(stencilarr.length, stencilarr.length)(lambdaNeigh, stencilarr, weightsArr)

    if (printOutput) printOriginalAndOutput(stencilarr, output, size)

    assertArrayEquals(compareData, output, delta)

  }

  @Test
  def testStencil2DSimpleAccessTwoWeightsMultConstOne(): Unit = {

    val compareData = Array(10.0f, 20.0f, 30.0f, 40.0f, 50.0f, 39.0f,
      13.0f, 26.0f, 39.0f, 52.0f, 65.0f, 57.0f,
      13.0f, 26.0f, 39.0f, 52.0f, 65.0f, 57.0f,
      13.0f, 26.0f, 39.0f, 52.0f, 65.0f, 57.0f,
      13.0f, 26.0f, 39.0f, 52.0f, 65.0f, 57.0f,
      10.0f, 20.0f, 30.0f, 40.0f, 50.0f, 39.0f)

    val constant = 3.0f

    /* u[cp] = S*l2 + u[cp] */

    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, SizeVar("M")), SizeVar("N")),
      ArrayType(ArrayType(Float, weights(0).length), weights.length),
      ArrayType(ArrayType(Float, weightsMiddle(0).length), weightsMiddle.length),
      (mat, weights, weightsMiddle) => {
        MapGlb(1)(
          MapGlb(0)(fun(n => {
            toGlobal(MapSeq(addTuple)) $ Zip(
              ReduceSeq(mult, constant) o ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\(tuple => Zip(tuple._0, tuple._1))) $ Zip(n, weights),
              ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\(tuple => Zip(tuple._0, tuple._1))) $ Zip(n, weightsMiddle)
            )
          }))) o Slide2D(slidesize, slidestep) $ mat
      })

    //    Compile(lambdaNeigh)

    val (output: Array[Float], runtime) = Execute(stencilarr.length, stencilarr.length)(lambdaNeigh, stencilarr, weights, weightsMiddle)
    if (printOutput) printOriginalAndOutput(stencilarr, output, size)

    assertArrayEquals(compareData, output, delta)

  }

  @Ignore // KEEP THIS
  @Test
  def testStencil2DSimpleTimesConstantPlusSelfPlusPrevious(): Unit = // Nothing here, just aborted ideas
  {

    val computeTwoStencils = Join() o (fun(tuple => {
      val left = Get(tuple, 0)
      val right = Get(tuple, 1)
      // only possible because reduce returns array of size 1!
      Zip(
        ReduceSeq(add, 0.0f) o Join() $ left,
        ReduceSeq(add, 0.0f) o Join() $ right)
    }))

    val dataBeforeCompute = fun(inputTile => Join() o computeTwoStencils o Split(dim) $ Zip(
      Join() o Slide2D(3, 1) $ inputTile,
      Join() o Slide2D(3, 1) $ inputTile
    ))

    val lambdaTwoStencil = fun(
      ArrayType(ArrayType(Float, SizeVar("M")), SizeVar("N")),
      (inp) => {
        MapGlb(1)(
          MapGlb(0)(
            toGlobal(MapSeqUnroll(id)) o
              toGlobal(MapSeq(addTuple)) o dataBeforeCompute)) $ inp
      })

    /*
      val f2 = fun(
        ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
        ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
        (matrix1, matrix2) =>
          MapGlb(1)(
            MapGlb(0)(fun((r) => {
              MapSeq(id) $ Get(r, 0)
            }      ))) $ Zip(matrix1, matrix2)
      )
    */

    /* Idea:
        - Pass in neighborhood (non-zipped)
        - Save the neighborhood in a function
        - use "scala" primitives to "fake" the data how you want it
        - reduce once and save
        - reduce twice and save
        - combine two reductions
     */

  }

  @Ignore // KEEP THIS
  @Test
  def testStencil2DSimpleAccessTwoWeightsBAD(): Unit = {
    /*
        Attempt to pull out using two stencils using zip2D / map2D / reduce2D
        ... which doesn't work
    */


    val constant = 3.0f

    val neighbourhoodFun = fun((nbh, w1, w2) => {
      val nbhw1 = zip2D(nbh, w1) :>> map2D(multTuple) :>> reduce2D(add, 0.0f)
      val nbhw2 = zip2D(nbh, w2) :>> map2D(multTuple) :>> reduce2D(add, 0.0f)

      Zip(nbhw1, nbhw2) :>> Map(addTuple) :>> toGlobal(MapSeqUnroll(id))
    })

    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, SizeVar("M")), SizeVar("N")),
      ArrayType(ArrayType(Float, weights(0).length), weights.length),
      ArrayType(ArrayType(Float, weightsMiddle(0).length), weightsMiddle.length),
      (mat, weights, weightsMiddle) => {
        MapGlb(1)(
          MapGlb(0)(fun(n =>
            neighbourhoodFun(n, weights, weightsMiddle)
          ))) o Slide2D(slidesize, slidestep) $ mat
      })

    val (output: Array[Float], runtime) = Execute(stencilarr.length, stencilarr.length)(lambdaNeigh, stencilarr, weights, weightsMiddle)
    if (printOutput) printOriginalAndOutput(stencilarr, output, size)
  }


  @Test
  def testStencil2DTwoGridsSwap(): Unit = {

    val compareData = Array(3.0f, 6.0f, 9.0f, 12.0f, 15.0f, 18.0f,
      3.0f, 6.0f, 9.0f, 12.0f, 15.0f, 18.0f,
      3.0f, 6.0f, 9.0f, 12.0f, 15.0f, 18.0f,
      3.0f, 6.0f, 9.0f, 12.0f, 15.0f, 18.0f,
      3.0f, 6.0f, 9.0f, 12.0f, 15.0f, 18.0f,
      3.0f, 6.0f, 9.0f, 12.0f, 15.0f, 18.0f)

    /* u[cp] = u1[cp] + u[cp] */

    val constant = 3.0f

    val f = fun(
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, weights(0).length), weights.length),
      ArrayType(ArrayType(Float, weightsMiddle(0).length), weightsMiddle.length),
      (matrix1, matrix2, wghts1, wghts2) => MapGlb(fun((m) =>
        MapSeq(fun(n => MapSeq(id) $ n))
          $ Get(m, 0)
      )) $ Zip((Join() $ (Slide2D(slidesize, slidestep) $ matrix1)), (Join() $ (Slide2D(slidesize, slidestep) $ matrix2)))
    )

    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, weights(0).length), weights.length),
      ArrayType(ArrayType(Float, weightsMiddle(0).length), weightsMiddle.length),
      (mat1, mat2, weights, weightsMiddle) => {
        MapGlb((fun((m) => {
          toGlobal(MapSeq(addTuple)) $ Zip(
            ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\(tuple => Zip(tuple._0, tuple._1))) $ Zip(Get(m, 0), weightsMiddle),
            ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\(tuple => Zip(tuple._0, tuple._1))) $ Zip(Get(m, 1), weightsMiddle)
          )
        }))) $ Zip((Join() $ (Slide2D(slidesize, slidestep) $ mat1)), (Join() $ (Slide2D(slidesize, slidestep) $ mat2)))

      })

    //    Compile(lambdaNeigh)

    val (output: Array[Float], runtime) = Execute(stencilarr.length, stencilarr.length)(lambdaNeigh, stencilarr, stencilarrCopy, weights, weightsMiddle)
    //    val (output: Array[Float], runtime) = Execute(stencilarr.length, stencilarr.length)(f, stencilarr, stencilarrCopy)
    if (printOutput) printOriginalAndOutput(stencilarr, output, size)

    assertArrayEquals(compareData, output, delta)

  }

  @Test
  def twoGridSwapWith3DifferentWeightsAndConstants(): Unit = {

    val compareData = Array(22.0f, 44.0f, 66.0f, 88.0f, 110.0f, 90.0f,
      28.0f, 56.0f, 84.0f, 112.0f, 140.0f, 126.0f,
      28.0f, 56.0f, 84.0f, 112.0f, 140.0f, 126.0f,
      28.0f, 56.0f, 84.0f, 112.0f, 140.0f, 126.0f,
      28.0f, 56.0f, 84.0f, 112.0f, 140.0f, 126.0f,
      22.0f, 44.0f, 66.0f, 88.0f, 110.0f, 90.0f)

    /* u[cp] = S*l1 + u[cp]*l2 */

    val constant1 = 4.0f
    val constant2 = 3.0f

    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, weights(0).length), weights.length),
      ArrayType(ArrayType(Float, weightsMiddle(0).length), weightsMiddle.length),
      (mat1, mat2, weights, weightsMiddle) => {
        MapGlb((fun((m) => {
          toGlobal(MapSeq(addTuple)) $ Zip(
            ReduceSeq(mult, constant1) o ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\(tuple => Zip(tuple._0, tuple._1))) $ Zip(Get(m, 0), weightsMiddle),
            ReduceSeq(mult, constant2) o ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\(tuple => Zip(tuple._0, tuple._1))) $ Zip(Get(m, 1), weights)
          )
        }))) $ Zip((Join() $ (Slide2D(slidesize, slidestep) $ mat1)), (Join() $ (Slide2D(slidesize, slidestep) $ mat2)))

      })

    val (output: Array[Float], runtime) = Execute(stencilarr.length, stencilarr.length)(lambdaNeigh, stencilarr, stencilarrCopy, weights, weightsMiddle)
    if (printOutput) printOriginalAndOutput(stencilarr, output, size)
    assertArrayEquals(compareData, output, delta)

  }

  @Test
  def twoGridSwapWith3DifferentWeightsAndConstantsPlusSelf(): Unit = {

    val compareData = Array(11.0f, 22.0f, 33.0f, 44.0f, 55.0f, 52.0f,
      13.0f, 26.0f, 39.0f, 52.0f, 65.0f, 64.0f,
      13.0f, 26.0f, 39.0f, 52.0f, 65.0f, 64.0f,
      13.0f, 26.0f, 39.0f, 52.0f, 65.0f, 64.0f,
      13.0f, 26.0f, 39.0f, 52.0f, 65.0f, 64.0f,
      11.0f, 22.0f, 33.0f, 44.0f, 55.0f, 52.0f)

    /* u[cp] = X * ( S*l1 + u[cp]*l2 + u1[cp]*l3) */

    val constant0 = 2.0f
    val constant1 = 4.0f
    val constant2 = 3.0f
    val X = 0.5f

    /** ** Why doesn't this work? !!!! ****/
    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, weights(0).length), weights.length),
      ArrayType(ArrayType(Float, weightsMiddle(0).length), weightsMiddle.length),
      (mat1, mat2, weights, weightsMiddle) => {
        MapGlb((fun((m) => {
          toGlobal(ReduceSeq(mult, X) o (addTuple)) $ Zip(
            ReduceSeq(mult, constant1) o ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\(tuple => Zip(tuple._0, tuple._1))) $ Zip(Get(m, 0), weightsMiddle),
            ReduceSeq(mult, constant2) o ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\((tuple1) => Zip(Get(Get(tuple1, 0), 0), Get(Get(tuple1, 0), 0)))) $ Zip(Zip(Get(m, 1), weights), Zip(Get(m, 1), weightsMiddle))
          )
        }))) $ Zip((Join() $ (Slide2D(slidesize, slidestep) $ mat1)), (Join() $ (Slide2D(slidesize, slidestep) $ mat2)))
      })

    val lambdaNeigh2 = fun(
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, weights(0).length), weights.length),
      ArrayType(ArrayType(Float, weightsMiddle(0).length), weightsMiddle.length),
      (mat1, mat2, weights, weightsMiddle) => {
        MapGlb((fun((m) => {
          toGlobal(MapSeq(id) o ReduceSeq(mult, X) o MapSeq(addTuple)) $ Zip(
            ReduceSeq(mult, constant1) o ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\(tuple => Zip(tuple._0, tuple._1))) $ Zip(Get(m, 0), weightsMiddle),
            MapSeq(addTuple) $ Zip(ReduceSeq(mult, constant0) o ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\((tuple1) => Zip(tuple1._0, Get(Get(tuple1, 1), 0)))) $ Zip(Get(m, 1), Zip(weights, weightsMiddle)),
              ReduceSeq(mult, constant2) o ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\((tuple1) => Zip(tuple1._0, Get(Get(tuple1, 1), 1)))) $ Zip(Get(m, 1), Zip(weights, weightsMiddle)))
          )
        }))) $ Zip((Join() $ (Slide2D(slidesize, slidestep) $ mat1)), (Join() $ (Slide2D(slidesize, slidestep) $ mat2)))
      })


    val (output: Array[Float], runtime) = Execute(stencilarr.length, stencilarr.length)(lambdaNeigh2, stencilarr, stencilarrCopy, weights, weightsMiddle)
    if (printOutput) printOriginalAndOutput(stencilarr, output, size)
    assertArrayEquals(compareData, output, delta)

  }

 /* let's iterate */


  @Test
  def testSimpleStencilIterate5(): Unit = {

    /* u[cp] = S */

    val compareData = Array(
      462.0f,917.0f,1337.0f,1589.0f,1526.0f,938.0f,
      791.0f,1575.0f,2289.0f,2765.0f,2611.0f,1652.0f,
      945.0f,1883.0f,2751.0f,3311.0f,3171.0f,1981.0f,
      945.0f,1883.0f,2751.0f,3311.0f,3171.0f,1981.0f,
      791.0f,1575.0f,2289.0f,2765.0f,2611.0f,1652.0f,
      462.0f,917.0f,1337.0f,1589.0f,1526.0f,938.0f
    )

    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, SizeVar("M")), SizeVar("N")),
      ArrayType(Float, weightsArr.length),
      (mat, weights) => {
        MapGlb(1)(
          MapGlb(0)(fun(neighbours => {
            toGlobal(MapSeqUnroll(id)) o
              ReduceSeqUnroll(fun((acc, pair) => {
                val pixel = Get(pair, 0)
                val weight = Get(pair, 1)
                multAndSumUp.apply(acc, pixel, weight)
              }), 0.0f) $ Zip(Join() $ neighbours, weights)
          }))
        ) o Slide2D(slidesize, slidestep) $ mat
      })


    // there must be a better way ...
    var input = stencilarr
    var outputX = Array[Float]()
    var runtime = 0.0f

    for(x <- 1 to iter) {
      val (output: Array[Float], runtime) = Execute(input.length, input.length)(lambdaNeigh, input, weightsArr)
      if(printOutput) printOriginalAndOutput(input, output, size)
      // need to re-pad, then slide and iterate
      input = createFakePaddingFloat(output.sliding(size,size).toArray,0.0f)
      outputX = output
    }

    assertArrayEquals(compareData, outputX, delta)

  }

  @Test
  def testStencil2DTwoGridsSwapIterate5(): Unit = {

    val compareData = Array(
    21.0f,42.0f,63.0f,84.0f,105.0f,126.0f,
    21.0f,42.0f,63.0f,84.0f,105.0f,126.0f,
    21.0f,42.0f,63.0f,84.0f,105.0f,126.0f,
    21.0f,42.0f,63.0f,84.0f,105.0f,126.0f,
    21.0f,42.0f,63.0f,84.0f,105.0f,126.0f,
    21.0f,42.0f,63.0f,84.0f,105.0f,126.0f
    )

    /* u[cp] = u1[cp] + u[cp] */

    val constant = 3.0f

    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, stencilarr.length), stencilarr.length),
      ArrayType(ArrayType(Float, weights(0).length), weights.length),
      ArrayType(ArrayType(Float, weightsMiddle(0).length), weightsMiddle.length),
      (mat1, mat2, weights, weightsMiddle) => {
        MapGlb((fun((m) => {
          toGlobal(MapSeq(addTuple)) $ Zip(
            ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\(tuple => Zip(tuple._0, tuple._1))) $ Zip(Get(m, 0), weightsMiddle),
            ReduceSeq(add, 0.0f) o Join() o MapSeq(ReduceSeq(add, id $ 0.0f) o MapSeq(multTuple)) o Map(\(tuple => Zip(tuple._0, tuple._1))) $ Zip(Get(m, 1), weightsMiddle)
          )
        }))) $ Zip((Join() $ (Slide2D(slidesize, slidestep) $ mat1)), (Join() $ (Slide2D(slidesize, slidestep) $ mat2)))

      })

    var inputArr = stencilarr
    var inputArrCopy = stencilarrCopy
    var outputX = Array[Float]()
    var runtime = 0.0f

    for(x <- 1 to iter)
    {
      // why does this zip work but not the other one ? ? ? (in SimpleRoom..)
      val (output: Array[Float], runtime) = Execute(stencilarr.length, stencilarr.length)(lambdaNeigh, inputArr, inputArrCopy, weights, weightsMiddle)
      if(printOutput) printOriginalAndOutput(stencilarr, output, size)

      inputArr = inputArrCopy
      inputArrCopy = createFakePaddingFloat(output.sliding(size,size).toArray,0.0f)

      outputX = output
    }

    assertArrayEquals(compareData, outputX, delta)

  }


  @Test
  def testSimpleStencilAsym1(): Unit = {

    /* u[cp] = S */

    val asymDimX = 10
    val asymDimY = 14

    val stencilarr = createDataFloat(asymDimY,asymDimX)

    val compareData = Array(
    462.0f,924.0f,1386.0f,1848.0f,2310.0f,2761.0f,3157.0f,3289.0f,2926.0f,1738.0f,
    792.0f,1584.0f,2376.0f,3168.0f,3960.0f,4741.0f,5412.0f,5709.0f,5016.0f,3058.0f,
    957.0f,1914.0f,2871.0f,3828.0f,4785.0f,5731.0f,6567.0f,6919.0f,6171.0f,3718.0f,
    1012.0f,2024.0f,3036.0f,4048.0f,5060.0f,6061.0f,6952.0f,7359.0f,6556.0f,3993.0f,
    1023.0f,2046.0f,3069.0f,4092.0f,5115.0f,6127.0f,7029.0f,7447.0f,6655.0f,4048.0f,
    1024.0f,2048.0f,3072.0f,4096.0f,5120.0f,6133.0f,7036.0f,7455.0f,6664.0f,4058.0f,
    1024.0f,2048.0f,3072.0f,4096.0f,5120.0f,6133.0f,7036.0f,7455.0f,6664.0f,4058.0f,
    1024.0f,2048.0f,3072.0f,4096.0f,5120.0f,6133.0f,7036.0f,7455.0f,6664.0f,4058.0f,
    1024.0f,2048.0f,3072.0f,4096.0f,5120.0f,6133.0f,7036.0f,7455.0f,6664.0f,4058.0f,
    1023.0f,2046.0f,3069.0f,4092.0f,5115.0f,6127.0f,7029.0f,7447.0f,6655.0f,4048.0f,
    1012.0f,2024.0f,3036.0f,4048.0f,5060.0f,6061.0f,6952.0f,7359.0f,6556.0f,3993.0f,
    957.0f,1914.0f,2871.0f,3828.0f,4785.0f,5731.0f,6567.0f,6919.0f,6171.0f,3718.0f,
    792.0f,1584.0f,2376.0f,3168.0f,3960.0f,4741.0f,5412.0f,5709.0f,5016.0f,3058.0f,
    462.0f,924.0f,1386.0f,1848.0f,2310.0f,2761.0f,3157.0f,3289.0f,2926.0f,1738.0f
    )

    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, SizeVar("M")), SizeVar("N")),
      ArrayType(Float, weightsArr.length),
      (mat, weights) => {
        MapGlb(1)(
          MapGlb(0)(fun(neighbours => {
            toGlobal(MapSeqUnroll(id)) o
              ReduceSeqUnroll(fun((acc, pair) => {
                val pixel = Get(pair, 0)
                val weight = Get(pair, 1)
                multAndSumUp.apply(acc, pixel, weight)
              }), 0.0f) $ Zip(Join() $ neighbours, weights)
          }))
        ) o Slide2D(slidesize, slidestep) $ mat
      })


    // there must be a better way ...
    var input = stencilarr
    var outputX = Array[Float]()
    var runtime = 0.0f

    for(x <- 1 to iter) {
      val (output: Array[Float], runtime) = Execute(input.length, input.length)(lambdaNeigh, input, weightsArr)
      if(printOutput) printOriginalAndOutput(input, output, asymDimX)
      // need to re-pad, then slide and iterate
      input = createFakePaddingFloat(output.sliding(asymDimX,asymDimX).toArray,0.0f)
      outputX = output
    }

    assertArrayEquals(compareData, outputX, delta)

  }


  /////////////////// JUNKYARD ///////////////////
  @Ignore
  @Test
  def testScalaData(): Unit =
  {
    stencilarr.transpose.map( x => x.sliding(3,1).toArray).sliding(3,1).toArray

  }


  @Ignore
  @Test
  def testZip2DEffects(): Unit =
  {

    val function = fun(
      ArrayType(ArrayType(Float, dim), dim),
      ArrayType(ArrayType(Float, dim), dim),
      (A, B) => {
        MapGlb(fun(t => {
          MapSeq(fun(x => add.apply(x, Get(t, 1)))) $ Get(t, 0)
        }))
      } $ Zip(A, B)
    )

    val f = fun(
      ArrayType(TupleType(Float, Float), dim),
      ArrayType(TupleType(Float, Float), dim),
      (left, right) =>
        MapGlb(1)(
          MapGlb(0)(fun(zippedMat => {
            val currentStencil = zippedMat._0
            val futureStencil = zippedMat._1
            toGlobal(MapSeqUnroll(id)) o
              ReduceSeqUnroll(add,futureStencil) $ zippedMat
          }))) $ Zip(left, right)
    )

    val lambdaNeigh = fun(
      ArrayType(ArrayType(Float, dim), dim),
      ArrayType(ArrayType(Float, dim), dim),
      (mat1, mat2) => {
        MapGlb(1)(
          MapGlb(0)(fun(zippedMat => {
            val currentStencil = zippedMat._0
            val futureStencil = zippedMat._1
            toGlobal(MapSeqUnroll(id)) o
              ReduceSeqUnroll(add,futureStencil) $ zippedMat
          }))) $ Zip(mat1,mat2)
      })

    val (output: Array[Float], runtime) = Execute(stencilarr.length, stencilarrCopy.length)(function, stencilarr, stencilarrCopy)

    print2DArray(stencilarr)
    println("*********************")
    print1DArrayAs2DArray(output,size)
  }

  @Ignore
  @Test
  def testZip1DEffects(): Unit =
  {

    val filling1 = Array.tabulate(size){ i => i+1}
    val filling2 = Array.tabulate(size){ i => i*2+1}

    val gold = (filling1,filling2).zipped.map(_+_)

    //    val test = (filling1,filling2).zipped(0)
    val test = gold(0)
    println("test: " +test)
    gold.foreach(println)

    //    gold.foreach(x => println("0: " + x._0 +  " 1: " + x._1))

    /*    val function = fun(
          ArrayType(Float, dim),
          ArrayType(Float, dim),
          (A, B) =>
            MapGlb(fun(t => {
              MapSeq(add)
            })) $ Zip(A, B)
        )
    */
    print1DArray(filling1)
    print1DArray(filling2)
    // val (output: Array[Float], runtime) = Execute(filling1.length, filling2.length)(function, filling1, filling2)

    println("*********************")
    //print1DArray(output)
  }

  def scalaSlide2D(input: Array[Array[Float]],
                   size1: Int, step1: Int,
                   size2: Int, step2: Int) = {
    val firstSlide = input.sliding(size1, step1).toArray
    val secondSlide = firstSlide.map(x => x.transpose.sliding(size2, step2).toArray)
    val neighbours = secondSlide.map(x => x.map(y => y.transpose))
    neighbours
  }

  def leggySlide2D(input: Array[Array[Float]],
                   size1: Int, step1: Int,
                   size2: Int, step2: Int) =
  {

    val first = input.drop(1).dropRight(1).sliding(3, 1).toArray
    val second = input.transpose.drop(1).dropRight(1).sliding(3, 1).toArray

    val firsec = first(0) ++ second(0)
    Array.fill(1)(Array.fill(1)(firsec))
  }

  @Ignore
  @Test
  def testLeggyGroup(): Unit = {

    val data2D = Array.tabulate(3, 3) { (i, j) => 3 * i + j }.map(x => x.map(_.toFloat))

    println(leggySlide2D(data2D, 3,1,3,1).deep.mkString(","))
    println("*******")
    println(scalaSlide2D(data2D, 3, 1, 3, 1).deep.mkString(","))
  }


}
