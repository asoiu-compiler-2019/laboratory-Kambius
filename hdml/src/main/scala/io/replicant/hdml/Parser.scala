package io.replicant.hdml
import java.lang.{String => JString}

import cats.syntax.either._
import fastparse.ScalaWhitespace._
import fastparse.{parse => fparce, _}
import io.replicant.hdml.Ast._

object Parser {
  private def maybeSpace[_: P]: P[Unit] =
    P(CharsWhileIn(" \t\r\n", 0))

  private def space[_: P]: P[Unit] =
    P(CharsWhileIn(" \t\r\n", 1))

  private def identifier[_: P]: P[String] =
    P(CharsWhileIn("a-zA-Z", 1).!)

  private def merge[_: P]: P[Merge] =
    P(("merge" ~~ space ~/ identifier.! ~ params ~ ":" ~ mergeBody).map(Merge.tupled))

  private def mergeBody[_: P]: P[MergeBody] =
    P(rootMerge | fieldsMerge)

  private def rootMerge[_: P]: P[RootMerge] =
    P(("$" ~ ".$" ~~ identifier ~ maybeCallParams).map(RootMerge.tupled))

  private def fieldsMerge[_: P]: P[FieldsMerge] =
    P(fieldMerge.rep(1, sep = ","./).map(FieldsMerge))

  private def fieldMerge[_: P]: P[(String, RootMerge)] =
    P(("$" ~ "." ~~ (identifier | "*".!) ~ ".$" ~~ identifier ~ maybeCallParams).map(r =>
      (r._1, RootMerge(r._2, r._3))))

  private def func[_: P]: P[Function] =
    P(("func" ~~ space ~/ identifier.! ~ params ~ ":" ~ value).map(Function.tupled))

  private def definition[_: P]: P[Definition] =
    P(merge | func)

  private def program[_: P]: P[Program] =
    P(maybeSpace ~ definition.rep(sep = &("func" | "merge")./).map(Program) ~ End)

  private def params[_: P]: P[Seq[String]] =
    P(("(" ~/ identifier.rep(sep = ",") ~ ")") | Pass(Nil))

  private def digits[_: P]: P[Unit] =
    P(CharsWhileIn("0-9"))

  private def exponent[_: P]: P[Unit] =
    P(CharIn("eE") ~ CharIn("+\\-").? ~ digits)

  private def fractional[_: P]: P[Unit] =
    P("." ~ digits)

  private def integral[_: P]: P[Unit] =
    P("0" | CharIn("1-9") ~ digits.?)

  private def number[_: P]: P[Literal.Number] =
    P(CharIn("+\\-").? ~ integral ~ fractional.? ~ exponent.?).!.map(x => Literal.Number(x.toDouble))

  private def `null`[_: P]: P[Literal.Null.type] =
    P("null").map(_ => Literal.Null)

  private def `false`[_: P]: P[Literal.Boolean] =
    P("false").map(_ => Literal.Boolean(false))

  private def `true`[_: P]: P[Literal.Boolean] =
    P("true").map(_ => Literal.Boolean(true))

  private def escape[_: P]: P[Unit] =
    P("\\" ~ CharIn("\"/\\\\bfnrt"))

  private def binOperator[_: P]: P[String] =
    P(("+" | "-" | "*" | "/").! | ("==" | "!=" | "&&" | "||").! | (">" | "<" | "<=" | ">=").!)

  private def strChars[_: P]: P[Unit] =
    P(CharsWhile(c => c != '\"' && c != '\\'))

  private def string[_: P]: P[Literal.String] =
    P("\"" ~/ (strChars | escape).rep.! ~ "\"").map(Literal.String)

  private def array[_: P]: P[Literal.Array] =
    P("[" ~/ value.rep(sep = ","./) ~ "]").map(Literal.Array)

  private def pair[_: P]: P[(String, Value)] =
    P(string.map(_.value) ~/ ":" ~/ value)

  private def obj[_: P]: P[Literal.Object] =
    P("{" ~/ pair.rep(sep = ","./) ~ "}").map(Literal.Object)

  private def literal[_: P]: P[Literal] =
    P(obj | array | string | `true` | `false` | `null` | number)

  private def param[_: P]: P[Param] =
    P(identifier.map(Param))

  private def ifBegin[_: P]: P[Value] =
    P("if" ~~ space ~/ value ~~ space)

  private def ifThan[_: P]: P[Value] =
    P("than" ~~ space ~/ value ~~ space)

  private def ifElse[_: P]: P[Value] =
    P("else" ~~ space ~/ value)

  private def branch[_: P]: P[FuncCall] =
    P((ifBegin ~/ ifThan ~/ ifElse).map(r => FuncCall("if", Seq(r._1, r._2, r._3))))

  private def funcCall[_: P]: P[FuncCall] =
    P((identifier ~ callParams).map(FuncCall.tupled))

  private def operatorUse[_: P]: P[FuncCall] =
    P((inBinOpValue ~ binOperator ~ inBinOpValue).map(r => FuncCall(r._2, Seq(r._1, r._3))))

  private def callParams[_: P]: P[Seq[Value]] =
    P("(" ~/ value.rep(sep = ","./) ~ ")")

  private def maybeCallParams[_: P]: P[Seq[Value]] =
    P(("(" ~/ value.rep(sep = ","./) ~ ")").?.map(_.getOrElse(Nil)))

  private def pathRoot[_: P]: P[Value] =
    P(("(" ~ operatorUse ~ ")") | literal | ("(" ~ branch ~ ")") | funcCall | param | ("(" ~ pathRoot ~ ")"))

  private def prop[_: P]: P[String] =
    P("." ~~ identifier)

  private def propCall[_: P]: P[(String, Seq[Value])] =
    P(".$" ~~ identifier ~ maybeCallParams)

  private def convertPath(value: Value, segments: Seq[Either[String, (String, Seq[Value])]]): Expression = {
    def construct(value: Value, s: Either[String, (String, Seq[Value])]): Expression = s match {
      case Left(name)            => Prop(value, name)
      case Right((name, params)) => PropCall(value, name, params)
    }

    segments.tail.foldLeft(construct(value, segments.head))(construct)
  }

  private def path[_: P]: P[Expression] =
    P((pathRoot ~ (prop.map(_.asLeft) | propCall.map(_.asRight)).rep(1, sep = ""./)).map(r => convertPath(r._1, r._2)))

  private def expression[_: P]: P[Expression] =
    P(NoCut((branch | funcCall | path | param) ~ !binOperator) | operatorUse)

  private def inBinOpValue[_: P]: P[Value] =
    P(literal ~ !"." | (branch | funcCall | path | param) | ("(" ~ value ~ ")"))
  private def value[_: P]: P[Value] =
    P(literal ~ !"." | expression | ("(" ~ value ~ ")"))

  def parse(s: String): Either[String, Program] =
    fparce(s, program(_), verboseFailures = true) match {
      case s: Parsed.Success[Program] => s.value.asRight
      case f: Parsed.Failure          => f.longMsg.asLeft
    }
}

object Ast {
  final case class Program(definitions: Seq[Definition])
  sealed trait Definition
  final case class Merge(name: String, params: Seq[String], body: MergeBody) extends Definition
  final case class Function(name: String, params: Seq[String], body: Value)  extends Definition

  sealed trait MergeBody
  final case class FieldsMerge(fieldMerges: Seq[(String, RootMerge)]) extends MergeBody
  final case class RootMerge(merge: String, params: Seq[Value])       extends MergeBody

  sealed trait Value
  sealed trait Literal    extends Value
  sealed trait Expression extends Value

  final case class Param(name: String)                                      extends Expression
  final case class Prop(value: Value, name: String)                         extends Expression
  final case class PropCall(value: Value, name: String, params: Seq[Value]) extends Expression
  final case class FuncCall(name: String, params: Seq[Value])               extends Expression

  object Literal {
    final case class Number(value: Double)                extends Literal
    final case class String(value: JString)               extends Literal
    final case class Boolean(value: scala.Boolean)        extends Literal
    case object Null                                      extends Literal
    final case class Array(value: Seq[Value])             extends Literal
    final case class Object(value: Seq[(JString, Value)]) extends Literal
  }
}
