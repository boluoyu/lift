package opencl.ir

import apart.arithmetic._
import arithmetic.TypeVar
import ir._
import ir.ast._
import opencl.ir.pattern._

/** Represents OpenCL address spaces either: local or global;
  * UndefAddressSpace should be used in case of errors */
abstract class OpenCLAddressSpace

object LocalMemory extends OpenCLAddressSpace {
  override def toString = "local"
}

object GlobalMemory extends OpenCLAddressSpace {
  override def toString = "global"
}

object PrivateMemory extends OpenCLAddressSpace {
  override def toString = "private"
}

case class AddressSpaceCollection(spaces: Seq[OpenCLAddressSpace])
  extends OpenCLAddressSpace {

  def findCommonAddressSpace(): OpenCLAddressSpace = {
    // try to find common address space which is not the private memory ...
    val noPrivateMem = spaces.filterNot(_== PrivateMemory)
    if (noPrivateMem.isEmpty) { // everything is in private memory
      return PrivateMemory
    }

    val addessSpaces = noPrivateMem.map({
      case coll: AddressSpaceCollection => coll.findCommonAddressSpace()
      case space => space
    })

    if (addessSpaces.forall(_ == addessSpaces.head)) {
      addessSpaces.head
    } else {
      throw new IllegalArgumentException("Could not determine " +
                                         "common addressSpace")
    }
  }
}

object UndefAddressSpace extends OpenCLAddressSpace

/** Represents memory in OpenCL as a raw collection of bytes allocated in an
  * OpenCL address space.
  *
  * @constructor Create a new OpenCLMemory object
  * @param variable The variable associated with the memory
  * @param size The size of the memory as numbers bytes
  * @param addressSpace The address space where the memory has been allocated
  */
class OpenCLMemory(var variable: Var,
                   val size: ArithExpr,
                   val addressSpace: OpenCLAddressSpace) extends Memory {

  // size cannot be 0 unless it is the null memory
  try {
    if (size.eval == 0)
      throw new IllegalArgumentException
  } catch {
    case _: NotEvaluableException => // nothing to do
    case e: Exception => throw e
  }

  // noe type variable allowed in the size
  if (TypeVar.getTypeVars(size).nonEmpty)
    throw new IllegalArgumentException

  def copy(): OpenCLMemory = {
    addressSpace match {
      case GlobalMemory => OpenCLMemory.allocGlobalMemory(size)
      case LocalMemory => OpenCLMemory.allocLocalMemory(size)
      case PrivateMemory => OpenCLMemory.allocPrivateMemory(size)
      case _ => this
    }
  }

  /** Debug output */
  override def toString: String = {
    this match {
      case coll: OpenCLMemoryCollection =>
        "[" + coll.subMemories.map(_.toString).reduce(_ + ", " + _) + "]"
      case _ =>
        "{" + variable + "; " + addressSpace + "; " + size + "}"
    }
  }

  def canEqual(other: Any): Boolean = other.isInstanceOf[OpenCLMemory]

  override def equals(other: Any): Boolean = other match {
    case that: OpenCLMemory =>
      (that canEqual this) &&
        variable == that.variable &&
        size == that.size &&
        addressSpace == that.addressSpace
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(variable, size, addressSpace)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}

class OpenCLMemoryCollection(val subMemories: Array[OpenCLMemory],
                             override val addressSpace: AddressSpaceCollection)
  extends OpenCLMemory(Var("Tuple"), subMemories.map(_.size).reduce(_+_),
                       addressSpace)

object OpenCLMemoryCollection {
  def apply(mems: Seq[OpenCLMemory]) = {
    val addressSpace = new AddressSpaceCollection(mems.map(_.addressSpace))
    new OpenCLMemoryCollection(mems.toArray, addressSpace)
  }
}

/** Represents the NULL OpenCL memory object */
object OpenCLNullMemory
  extends OpenCLMemory(Var("NULL"), Cst(-1), UndefAddressSpace)


object OpenCLMemory {

  def apply(variable: Var, size: ArithExpr,
            addressSpace: OpenCLAddressSpace): OpenCLMemory = {
    new OpenCLMemory(variable, size, addressSpace)
  }

  def asOpenCLMemory(m: Memory): OpenCLMemory = {
    m match {
      case oclm: OpenCLMemory => oclm
      case UnallocatedMemory => OpenCLNullMemory
      case _ => throw new IllegalArgumentException
    }
  }

  // checking for address spaces
  def containsAddressSpace(mem: Memory,
                           memType: OpenCLAddressSpace): Boolean = {
    mem match {
      case coll: OpenCLMemoryCollection =>
        coll.subMemories.exists(x => x.addressSpace == memType)
      case m: OpenCLMemory => m.addressSpace == memType
      case _ => false
    }
  }

  def containsGlobalMemory(mem: Memory): Boolean =
    containsAddressSpace(mem, GlobalMemory)

  def containsLocalMemory(mem: Memory): Boolean =
    containsAddressSpace(mem, LocalMemory)

  def containsPrivateMemory(mem: Memory): Boolean =
    containsAddressSpace(mem, PrivateMemory)

  /** Return newly allocated memory based on the given sizes and the address
    * space of the input memory
    *
    * @param glbOutSize Size in bytes to allocate in global memory
    * @param lclOutSize Size in bytes to allocate in local memory
    * @param addressSpace Address space for allocation
    * @return The newly allocated memory object
    */
  def allocMemory(glbOutSize: ArithExpr,
                  lclOutSize: ArithExpr,
                  pvtOutSize: ArithExpr,
                  addressSpace: OpenCLAddressSpace): OpenCLMemory = {
    assert(addressSpace != UndefAddressSpace)

    addressSpace match {
      case GlobalMemory => allocGlobalMemory(glbOutSize)
      case LocalMemory => allocLocalMemory(lclOutSize)
      case PrivateMemory => allocPrivateMemory(pvtOutSize)
    }
  }

  /** Return newly allocated global memory */
  def allocGlobalMemory(glbOutSize: ArithExpr): OpenCLMemory = {
    OpenCLMemory(Var(ContinuousRange(Cst(0), glbOutSize)),
                 glbOutSize, GlobalMemory)
  }

  /** Return newly allocated local memory */
  def allocLocalMemory(lclOutSize: ArithExpr): OpenCLMemory = {
    OpenCLMemory(Var(ContinuousRange(Cst(0), lclOutSize)),
                 lclOutSize, LocalMemory)
  }

  def allocPrivateMemory(size: ArithExpr): OpenCLMemory = {
    OpenCLMemory(Var(ContinuousRange(Cst(0), size)), size, PrivateMemory)
  }

  def getMaxSizeInBytes(t: Type): ArithExpr = {
    ArithExpr.max(getSizeInBytes(t))
  }

  def getSizeInBytes(t: Type): ArithExpr = t match {
    case st: ScalarType => st.size
    case vt: VectorType => vt.len * getSizeInBytes(vt.scalarT)
    case at: ArrayType => at.len * getSizeInBytes(at.elemT)
    case tt: TupleType => tt.elemsT.map(getSizeInBytes).reduce(_ + _)
    case _ => throw new TypeException(t, "??")
  }
}

/** Represents an OpenCLMemory object combined with a type.
  *
  * @constructor Create a new TypedOpenCLMemory object
  * @param mem The underlying memory object
  * @param t The type associated with the memory object
  */
case class TypedOpenCLMemory(mem: OpenCLMemory, t: Type) {
  override def toString = "(" + mem.toString +": " + t.toString + ")"
}

object TypedOpenCLMemory {
  def apply(expr: Expr): TypedOpenCLMemory = {
    new TypedOpenCLMemory(OpenCLMemory.asOpenCLMemory(expr.mem), expr.t)
  }

  def apply(mem: Memory, t: Type): TypedOpenCLMemory = {
    new TypedOpenCLMemory(OpenCLMemory.asOpenCLMemory(mem), t)
  }

  def get(expr: Expr,
          params: Seq[Param],
          includePrivate: Boolean = false): Seq[TypedOpenCLMemory] = {

    // nested functions so that `params` and `includePrivate` are in scope

    def collect(expr: Expr): Seq[TypedOpenCLMemory] = {
      expr match {
        case v: Value => collectValue(v)
        case p: Param => Seq()
        case call: FunCall => collectFunCall(call)
      }
    }

    def collectValue(v: Value): Seq[TypedOpenCLMemory] = {
      if (includePrivate) {
        Seq(TypedOpenCLMemory(v))
      } else {
        Seq()
      }
    }

    def collectFunCall(call: FunCall): Seq[TypedOpenCLMemory] = {
      val argMems: Seq[TypedOpenCLMemory] = call.args.length match {
        case 0 => Seq()
        case 1 => collect(call.args.head)
        case _ => call.args.map(collect).reduce(_ ++ _)
      }

      val adaptedArgMems = call.f match {
        case s: asScalar => adaptArgMemsAsScalar(argMems)
        case v: asVector => adaptArgsMemsAsVector(v, argMems)
        case _           => argMems
      }

      val bodyMems = call.f match {
        case uf: UserFun    => collectUserFun(call)
        case vf: VectorizeUserFun
                            => collectUserFun(call)
        case l: Lambda      => collect(l.body)
        case m: AbstractMap => collectMap(call.t, m)
        case r: AbstractPartRed => collectReduce(r, adaptedArgMems)
        case i: Iterate     => collectIterate(call, i)
        case fp: FPattern   => collect(fp.f.body)
        case _              => Seq()
      }

      adaptedArgMems ++ bodyMems
    }

    def adaptArgMemsAsScalar(mems: Seq[TypedOpenCLMemory]): Seq[TypedOpenCLMemory] = {
      if (mems.isEmpty) {
        mems
      } else {
        val tm = mems.last
        val at = tm.t.asInstanceOf[ArrayType]
        mems.init :+ TypedOpenCLMemory(tm.mem, Type.asScalarType(at))
      }
    }

    def adaptArgsMemsAsVector(v: asVector,
                              mems: Seq[TypedOpenCLMemory]): Seq[TypedOpenCLMemory] = {
      if (mems.isEmpty) {
        mems
      } else {
        val tm = mems.last
        mems.init :+ TypedOpenCLMemory(tm.mem, tm.t.vectorize(v.len))
      }
    }

    def collectUserFun(call: FunCall): Seq[TypedOpenCLMemory] = {
      call.mem match {
        case m: OpenCLMemory =>
          if (!includePrivate && m.addressSpace == PrivateMemory) {
            Seq()
          } else {
            Seq(TypedOpenCLMemory(call))
          }
      }
    }

    def collectMap(t: Type,
                   m: AbstractMap): Seq[TypedOpenCLMemory] = {
      val mems = collect(m.f.body)

      // change types
      mems.map( tm => tm.mem.addressSpace match {
        case GlobalMemory | PrivateMemory =>
          TypedOpenCLMemory(tm.mem, ArrayType(tm.t, Type.getLength(t)))

        case LocalMemory =>
          m match {
            case _: MapGlb | _: MapWrg  | _: Map =>
              tm
            case _: MapLcl | _: MapWarp | _: MapLane | _: MapSeq =>
              TypedOpenCLMemory(tm.mem, ArrayType(tm.t, Type.getLength(t)))
          }
      })
    }

    def collectReduce(r: AbstractPartRed,
                      argMems: Seq[TypedOpenCLMemory]): Seq[TypedOpenCLMemory] = {
      val mems = collect(r.f.body)

      mems.filter(m => {
        val isAlreadyInArgs   = argMems.exists(_.mem.variable == m.mem.variable)
        val isAlreadyInParams =  params.exists(_.mem.variable == m.mem.variable)

        !isAlreadyInArgs && !isAlreadyInParams
      })
    }

    def collectIterate(call: FunCall, i: Iterate): Seq[TypedOpenCLMemory] = {
      TypedOpenCLMemory(i.swapBuffer, ArrayType(call.args.head.t, ?)) +: collect(i.f.body)
    }

    // this prevents that multiple memory objects (possibly with different types) are collected
    // multiple times
    def distinct(seq: Seq[TypedOpenCLMemory]) = {
      val b = Seq.newBuilder[TypedOpenCLMemory]
      val seen = scala.collection.mutable.HashSet[OpenCLMemory]()
      for (x <- seq) {
        if (!seen(x.mem)) {
          b += x
          seen += x.mem
        }
      }
      b.result()
    }

    // actual function impl
    params.map(TypedOpenCLMemory(_)) ++ distinct(collect(expr))
  }
}
