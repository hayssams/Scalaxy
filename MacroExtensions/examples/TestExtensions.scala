object TestExtensions 
{  
  import scala.math.Numeric
  /*@extend(Array[T]) def avg[T <: Numeric[T]]: T = 
    if (self.isEmpty) implicitly[Numeric[T]].zero
    else (self.max + self.min)
    */
    
  @extend(Array[T]) def notNulls[T <: AnyRef]: Int =
    self.count(_ ne null)
    
  @extend(Int) def str0 { println(self.toString) }
  
  @extend(Any) def quoted(quote: String): String = quote + self + quote
  
  @extend(Int) def str1: String = self.toString
  
  @extend(Int) def str2: String = macro {
    println("EXECUTING EXTENSION MACRO!")
    reify(self.splice.toString)
  }
  /*
  @extend(Int) def str = macro reify(self.splice.toString)
  @extend(Int) def str = macro {
    ...
    reify(self.splice.toString)
  }
  */
  //println(10.str)
}
