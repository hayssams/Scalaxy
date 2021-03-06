/*
sbt clean && sbt "run 0" "run 1" "run 2" "run 3" "run 4"

; clean ; run 0 ; run 1 ; run 2 ; run 3 ; run 4
*/
import scalaxy.loops._
import scala.language.postfixOps

// TODO hygienize self and params (by value, not by name as currently)
object Run extends App {
  import TestUtils._
  
  val n = 10000
  def NaiveExtensions(a: Complex, b: Complex) = {
    import NaiveImplicits._
    var r = a
    for (i <- 0 until n optimized)
      r = r * b + a
    r
  }
  def InlineExtensions(a: Complex, b: Complex) = {
    import InlineImplicits._
    var r = a
    for (i <- 0 until n optimized)
      r = r * b + a
    r
  }
  def MacroExtensions(a: Complex, b: Complex) = {
    import MacroImplicits._
    var r = a
    for (i <- 0 until n optimized)
      r = r * b + a
    r
  }
  def NormalMembers(a: Complex, b: Complex) = {
    var r = a
    for (i <- 0 until n optimized)
      r = (r ** b) ++ a
    r
  }
  def InlineMembers(a: Complex, b: Complex) = {
    var r = a
    for (i <- 0 until n optimized)
      r = (r *** b) +++ a
    r
  }
  
  val x = Complex(1.02, 0.014)
  val y = Complex(0.8, 0.002)
  
  val Array(i) = args.map(_.toInt)
  i match {
    case 0 => tst(n, "Naive Extensions") { NaiveExtensions(x, y) }
    case 1 => tst(n, "Inline Extensions") { InlineExtensions(x, y) }
    case 2 => tst(n, "Macro Extensions") { MacroExtensions(x, y) }
    case 3 => tst(n, "Normal Members") { NormalMembers(x, y) }
    case 4 => tst(n, "Inline Members") { InlineMembers(x, y) }
  }
}
