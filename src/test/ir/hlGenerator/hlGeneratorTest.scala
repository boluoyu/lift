package ir.hlGenerator

import ir._
import ir.ast._
import ir.interpreter.Interpreter
import opencl.executor.{Compile, Execute, Executor}
import opencl.ir._
import opencl.ir.pattern.toGlobal
import org.junit.Assert._
import org.junit._
import rewriting.{EnabledMappings, Lower}

import scala.language.reflectiveCalls

object hlGeneratorTest{

  @BeforeClass
  def before(): Unit =
    Executor.loadLibrary()

  @AfterClass
  def after(): Unit =
    Executor.shutdown()

}

class hlGeneratorTest {

  @Test
  def testNewGen(): Unit = {
    val hlGen = new hlGenerator
    hlGen.generateProgram()
    assertTrue(hlGen.RefinedResult.nonEmpty)
  }

  @Ignore
  @Test
  def seekCompilerBugs(): Unit = {
    val f = fun(
      Float,
      ArrayType(ArrayType(Float,32),32),
      Float,
      ArrayType(Float,32),
      (p99,p102,p226,p239) =>{
        Map(fun((p24) =>
          Split(4) o Join() o Map(fun((p157) =>
            Reduce(fun((p20,p195)=>
              add(p20,p195)
            ))(toGlobal(add)(p24,p99),p157)
          )) $ p102
        ))(Reduce(fun((p215,p49) =>
        add(p215,p49)
        ))(add(p226,p226),p239))
      }
    )
    val fs = Lower.mapCombinations(f,
      EnabledMappings(global0 = true, global01 = false, global10 = false,
        group0 = false, group01 = false, group10 = false))
    TypeChecker(fs.head)
    val code = Compile(fs.head)
    val Args = scala.collection.mutable.ArrayBuffer[Any]()
    for (j <- f.params.indices) {
      f.params(j).t match {
        case ArrayType(ArrayType(Float, l1), l2) =>
          Args += Array.fill(l1.eval, l2.eval)(1.0f)
        case ArrayType(Float, l1) =>
          Args += Array.fill(l1.eval)(2.0f)
        case Float =>
          Args += 3.0f
        case _=>
      }
    }

    val output_int = Interpreter(f).->[Vector[Vector[Float]]].runAndFlatten(Args:_*).toArray[Float]
    val(output_exe:Array[Float],_)= Execute(1,32)(code,fs.head,Args:_*)
    assertArrayEquals(output_int, output_exe, 0.0f)
  }


  @Ignore
  @Test
  def seekExeBugs(): Unit = {
    val f = fun(
      ArrayType(Float,32),
      ArrayType(Float,32),
      (p101,p241) =>{
        Map(fun((p66) =>
          Reduce(fun((p171,p223)=>
            add(p171,p223)
          ))(p66,p101)
        ))(p241)
      }
    )
    val fs = Lower.mapCombinations(f,
      EnabledMappings(global0 = true, global01 = false, global10 = false,
        group0 = false, group01 = false, group10 = false))
    val test = rewriting.Rewrite.rewriteJustGenerable(fs.head,rewriting.allRules,5)
    for(i<- test.indices) {
      val rewrited = test(i)

      TypeChecker(rewrited)
      val code = Compile(rewrited)
      val Args = scala.collection.mutable.ArrayBuffer[Any]()
      for (j <- f.params.indices) {
        f.params(j).t match {
          case ArrayType(ArrayType(Float, l1), l2) =>
            Args += Array.fill(l1.eval, l2.eval)(1.0f)
          case ArrayType(Float, l1) =>
            Args += Array.fill(l1.eval)(2.0f)
          case Float =>
            Args += 3.0f
          case _ =>
        }
      }
      val output_int = Interpreter(f).->[Vector[Vector[Float]]].runAndFlatten(Args: _*).toArray[Float]
      val (output_exe: Array[Float], _) = Execute(1, 32)(code, rewrited, Args: _*)
      assertArrayEquals(output_int, output_exe, 0.0f)
    }

  }

  @Ignore
  @Test
  def seekExeBugs1(): Unit = {
    for(_ <- 0 until 10000){

      val f = fun(
        Float,
        ArrayType(Float,32),
        ArrayType(Float,32),
        (p236,p116,p93) =>{
          Reduce(fun((p183,p247) =>
            Map(fun((p18) =>
              add(p247,p18)
            )) $ p183
          ))(Map(fun((p18) =>
            add(p236,p18)
          ))(p116),p93)

        }
      )
      val fs = Lower.mapCombinations(f,
        EnabledMappings(global0 = true, global01 = false, global10 = false,
          group0 = false, group01 = false, group10 = false))
      TypeChecker(fs.head)
      val code = Compile(fs.head)
      val Args = scala.collection.mutable.ArrayBuffer[Any]()
      for (j <- f.params.indices) {
        f.params(j).t match {
          case ArrayType(ArrayType(Float, l1), l2) =>
            Args += Array.fill(l1.eval, l2.eval)(1.0f)
          case ArrayType(Float, l1) =>
            Args += Array.fill(l1.eval)(2.0f)
          case Float =>
            Args += 3.0f
          case _=>
        }
      }
      val output_int = Interpreter(f).->[Vector[Vector[Float]]].runAndFlatten(Args:_*).toArray[Float]
      val(output_exe:Array[Float],_)= Execute(1,32)(code,fs.head,Args:_*)
      assertArrayEquals(output_int, output_exe, 0.0f)

    }
  }

  @Ignore
  @Test
  def ResultNotEqualBugs(): Unit = {
    val f = fun(
      Float,
      ArrayType(Float,32),
      ArrayType(Float,32),
      (p236,p116,p93) =>{
        Reduce(fun((p183,p247) =>
          Map(fun((p18) =>
            add(p247,p18)
          )) $ p183
        ))(Map(fun((p18) =>
          add(p236,p18)
        ))(p116),p93)

      }
    )
    TypeChecker(f)
    val fs = Lower.mapCombinations(f, EnabledMappings(global0 = true, global01 = true, global10 = true, group0 = true, group01 = true, group10 = true))
    val lower = fs.head
    TypeChecker(lower)
    val code = Compile(lower)
    val Args = scala.collection.mutable.ArrayBuffer[Any]()
    for (j <- f.params.indices) {
      f.params(j).t match {
        case ArrayType(ArrayType(Float, l1), l2) =>
          Args += Array.fill(l1.eval, l2.eval)(1.0f)
        case ArrayType(Float, l1) =>
          Args += Array.fill(l1.eval)(2.0f)
        case Float =>
          Args += 3.0f
        case _=>
      }
    }

    val output_int = Interpreter(f).->[Vector[Vector[Float]]].runAndFlatten(Args:_*).toArray[Float]
    val(output_exe:Array[Float],_)= Execute(1,1)(code,lower,Args:_*)
    assertArrayEquals(output_int, output_exe, 0.0f)
  }


}
