package scalaxy; package components

object MatchActionDefinitions 
{
  //import scala.reflect.api._
  //import scala.reflect.runtime._
  //import scala.reflect.runtime.Universe._
  import scala.reflect.mirror._
  import definitions._
  
  //private lazy val ReplacementClass = staticClass("scalaxy.Replacement")//definitions.getClass(newTypeName("scalaxy.Replacement"))
  private lazy val MatchActionClass = staticClass("scalaxy.MatchAction")
  
  private lazy val defaultValues = Map(
    IntClass.asType -> 0,
    ShortClass.asType -> (0: Short),
    LongClass.asType -> (0: Long),
    ByteClass.asType -> (0: Byte),
    FloatClass.asType -> 0f,
    DoubleClass.asType -> 0.0,
    BooleanClass.asType -> false,
    CharClass.asType -> '\0'
  )
  private def getDefaultValue(tpe: Type): Any = 
    defaultValues.get(tpe).getOrElse(null)
  
  def invokeMethod(target: AnyRef, method: Symbol, params: List[AnyRef]) = {
    //println("target = " + target)
    if (true) {
      val methodName = method.name.toString
      val javaMethod = target.getClass.getDeclaredMethods.find(_.getName == methodName).getOrElse(
        throw new RuntimeException("No method '" + methodName + "' found in " + target.getClass.getName + ":\n" + getClass.getDeclaredMethods.mkString("\n"))
      )
      javaMethod.invoke(target, params:_*)
      //println("mm = " + mm)
    } else {
      invoke(target, method)(params)
    }
  }
  def getMatchActionDefinitions(holder: AnyRef): Seq[(String, MatchAction[Any])] = {
    //val holder = Example
    //val holderSym = staticClass("scalaxy.Example")
    //val holder = companionInstance(holderSym)
    val holderType = typeOfInstance(holder)
    //val holderCls = classOf[Example]
    //val holderSym = classToSymbol(holderCls)//staticClass("scalaxy.Example")
    //val holderType = classToType(holderCls)//holderSym.tpe//.deconst.dealias
    //val holder = holderCls.newInstance.asInstanceOf[AnyRef]
    
    def dbgStr(t: Type) = {
      t + " (" + t.getClass.getName + " <- " + t.getClass.getSuperclass.getName + ") = " + t//debugString(t)
    }
    object FollowMethodResult {
      def unapply(t: Type): Option[Type] = t match {
        case MethodType(_, r) =>
          unapply(r)
        case PolyType(_, mt) =>
          unapply(mt)
        case _ =>
          Some(t)
      }
    }
    val methods: Seq[(Symbol, Type)] = 
      holderType.members.filter(_.isMethod).map(m => (m, m.asType)).toSeq
      
    //println("Scanning holder " + holder + " : " + methods.size + " methods")
    //for (m <- methods) println("\t" + m.name)
      
    methods.collect({ 
      //case (m, PolyType(paramsyms, mt @ FollowMethodResult(result)))
      case (m, mt @ FollowMethodResult(result)) =>
      // TODO
      //if result.stat_<:<(MatchActionClass.asType) => // == ReplacementClass.asType =>
        def getParamTypes(t: Type): List[Type] = t match {
          case MethodType(tt, r) =>
            //println("Found method (" + m.name + ", m.type = " + dbgStr(m.asType) + ") : type " + dbgStr(t) + " with tt = " + tt + " and r = " + r)
            tt.map(_.asType) ++ getParamTypes(r)
          case PolyType(_, mt) =>
            getParamTypes(mt)
          case _ =>
            List()
        }
        val actualParamTypes = getParamTypes(mt)
        val defaultParams = 
          actualParamTypes.map(getDefaultValue(_).asInstanceOf[AnyRef])
          
        try {
          val r = invokeMethod(holder, m, defaultParams)
          //println("r = " + r + " : " + r.getClass.getName)
          if (r.isInstanceOf[MatchAction[_]])
            Some((holder + "." + m.name, r.asInstanceOf[MatchAction[Any]]))
          else 
            None
        } catch { case ex =>
          /*if (m.toString.contains("removeDevilConstant")) {
            ex.printStackTrace
            println("Method " + m + " failed with " + defaultParams.mkString(", ") + " : " + ex)
          }*/
          None
        }
      case (m, t) =>
        println("Unable to parse method '" + m.name + "' : " + dbgStr(t))
        None
    }).flatten
  }
}