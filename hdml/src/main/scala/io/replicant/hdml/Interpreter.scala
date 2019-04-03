package io.replicant.hdml

import cats.instances.either._
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.traverse._
import io.circe._
import io.circe.syntax._
import io.replicant.hdml.Ast._

import scala.reflect.ClassTag
import scala.util.Random

object Interpreter {
  def run(program: Program, documents: Seq[Document]): Either[String, Document] = {
    val functions = program.definitions.collect {
      case f: Function => f.name -> f
    }.toMap

    val merges = program.definitions.collect {
      case m: Merge => m.name -> m
    }.toMap

    def assertValue[T <: Literal: ClassTag](l: Literal): Either[String, T] =
      l match {
        case r: T =>
          r.asRight

        case _ =>
          s"Expected ${implicitly[ClassTag[T]].runtimeClass.getSimpleName} but got ${l.getClass.getSimpleName}".asLeft
      }

    def runBinFunction[L <: Literal: ClassTag, T](params: Seq[Value])(tr: (L, L) => Literal): Either[String, Literal] =
      params match {
        case Seq(a, b) =>
          for {
            ar <- runValue(a)
            al <- assertValue[L](ar)
            br <- runValue(b)
            bl <- assertValue[L](br)
          } yield tr(al, bl)

        case _ =>
          throw new IllegalArgumentException(s"Expected 2 params but got ${params.size}")
      }

    def runBuildInFunction(func: String, params: Seq[Value]): Either[String, Literal] =
      func match {
        case "if" =>
          params match {
            case Seq(cond, thanCase, elseCase) =>
              for {
                r <- runValue(cond)
                l <- assertValue[Literal.Boolean](r)
                f <- runValue(if (l.value) thanCase else elseCase)
              } yield f

            case _ =>
              s"Invalid number of if params: $params".asLeft
          }

        case "+" =>
          runBinFunction[Literal.Number, Literal.Number](params) { (a, b) =>
            Literal.Number(a.value + b.value)
          }

        case "-" =>
          runBinFunction[Literal.Number, Literal.Number](params) { (a, b) =>
            Literal.Number(a.value - b.value)
          }

        case "*" =>
          runBinFunction[Literal.Number, Literal.Number](params) { (a, b) =>
            Literal.Number(a.value * b.value)
          }

        case "/" =>
          runBinFunction[Literal.Number, Literal.Number](params) { (a, b) =>
            Literal.Number(a.value / b.value)
          }

        case "==" =>
          runBinFunction[Literal, Literal.Boolean](params) { (a, b) =>
            Literal.Boolean(a == b)
          }

        case "!=" =>
          runBinFunction[Literal, Literal.Boolean](params) { (a, b) =>
            Literal.Boolean(a != b)
          }

        case "&&" =>
          runBinFunction[Literal.Boolean, Literal.Boolean](params) { (a, b) =>
            Literal.Boolean(a.value && b.value)
          }

        case "||" =>
          runBinFunction[Literal.Boolean, Literal.Boolean](params) { (a, b) =>
            Literal.Boolean(a.value || b.value)
          }

        case "<" =>
          runBinFunction[Literal.Number, Literal.Boolean](params) { (a, b) =>
            Literal.Boolean(a.value < b.value)
          }

        case "<=" =>
          runBinFunction[Literal.Number, Literal.Boolean](params) { (a, b) =>
            Literal.Boolean(a.value <= b.value)
          }

        case ">" =>
          runBinFunction[Literal.Number, Literal.Boolean](params) { (a, b) =>
            Literal.Boolean(a.value > b.value)
          }

        case ">=" =>
          runBinFunction[Literal.Number, Literal.Boolean](params) { (a, b) =>
            Literal.Boolean(a.value < b.value)
          }

        case _ =>
          s"Function $func is not default".asLeft
      }

    def runCustomFunction(func: String, params: Seq[Value]): Either[String, Literal] =
      for {
        f <- functions.get(func).toRight(s"Function $func is not defined")
        _ <- Either.cond(f.params.size == params.size, (), s"Invalid number of params for $func call")
        r <- resolveParam(f.params.zip(params).toMap, f.body)
        l <- runValue(r)
      } yield l

    def runFunction(func: String, params: Seq[Value]): Either[String, Literal] =
      runBuildInFunction(func, params).orElse(runCustomFunction(func, params))

    def getProp(value: Value, name: String): Either[String, Literal] =
      runValue(value).flatMap {
        case Literal.Object(values) =>
          values.toMap.getOrElse(name, Literal.Null) match {
            case l: Literal => l.asRight
            case v          => throw new IllegalArgumentException(s"Non literal expression after run: $v")
          }

        case Literal.Null =>
          Literal.Null.asRight

        case v =>
          s"Can't get property $name of $v".asLeft
      }

    def runValue(value: Value): Either[String, Literal] = value match {
      case Literal.Array(values)  => values.toList.traverse(runValue).map(Literal.Array)
      case Literal.Object(values) => values.toList.traverse(kv => runValue(kv._2).map(kv._1 -> _)).map(Literal.Object)
      case l: Literal             => l.asRight
      case Prop(v, n)             => getProp(v, n)
      case PropCall(v, n, p)      => runFunction(n, v +: p)
      case FuncCall(n, p)         => runFunction(n, p)
      case _: Param               => throw new IllegalArgumentException("Params can't be run")
    }

    def runRootMerge(r: RootMerge, dx: Seq[Document]): Either[String, Document] =
      r match {
        case RootMerge("random", Nil) =>
          dx(Random.nextInt(dx.size)).copy(timestamp = dx.map(_.timestamp).max).asRight

        case RootMerge("timestamp", Nil) =>
          dx.maxBy(_.timestamp).asRight

        case RootMerge("fold", params) =>
          params match {
            case Seq(zero, Param(func)) =>
              dx.foldLeft(zero.asRight[String]) { (acc, b) =>
                  acc.flatMap { a =>
                    runFunction(func, Seq(a, b.json.map(Transforms.fromJson).filter(_ != Literal.Null).getOrElse(zero)))
                  }
                }
                .map(r => Document(Some(Transforms.toJson(r)), dx.map(_.timestamp).max))

            case _ =>
              s"Invalid params for fold: $params".asLeft
          }

        case RootMerge(merge, params) =>
          merges
            .get(merge)
            .fold(s"Merge $merge is not defined".asLeft[Document])(m => runMerge(m, params, dx))
      }

    def resolveParam(mappings: Map[String, Value], value: Value): Either[String, Value] =
      value match {
        case Param(name) =>
          mappings.get(name).toRight(s"Param $name not found!")

        case Literal.Object(pairs) =>
          pairs.toList
            .traverse(kv => resolveParam(mappings, kv._2).map(kv._1 -> _))
            .map(Literal.Object)

        case Literal.Array(values) =>
          values.toList.traverse(resolveParam(mappings, _)).map(Literal.Array)

        case p: Prop =>
          resolveParam(mappings, p.value).map(v => p.copy(value = v))

        case p: PropCall =>
          for {
            vl <- resolveParam(mappings, p.value)
            pr <- resolveParams(mappings, p.params)
          } yield p.copy(value = vl, params = pr)

        case f: FuncCall =>
          resolveParams(mappings, f.params).map(v => f.copy(params = v))

        case v: Value =>
          v.asRight
      }

    def resolveParams(mappings: Map[String, Value], values: Seq[Value]): Either[String, Seq[Value]] =
      values.toList.traverse(resolveParam(mappings, _))

    def resolveMergeParams(mappings: Map[String, Value], merge: RootMerge): Either[String, Seq[Value]] =
      merge.merge match {
        case "fold" => resolveParam(mappings, merge.params.head).map(_ +: merge.params.tail)
        case _      => resolveParams(mappings, merge.params)
      }

    def runMerge(m: Merge, p: Seq[Value], dx: Seq[Document]): Either[String, Document] =
      if (m.params.size != p.size) {
        s"Invalid params for merge ${m.name}".asLeft
      } else {
        val mappings = m.params.zip(p).toMap
        m.body match {
          case m: RootMerge =>
            resolveMergeParams(mappings, m).flatMap(params => runRootMerge(m.copy(params = params), dx))

          case FieldsMerge(mx) =>
            val subMerges = mx.toMap - "*"
            val dMerge    = mx.toMap.getOrElse("*", RootMerge("timestamp", Nil))
            val defMerge  = resolveMergeParams(mappings, dMerge).map(params => dMerge.copy(params = params))

            val subDocs = subMerges.map {
              case (field, rm) =>
                val subFields =
                  dx.map(d => Document(d.json.flatMap(_.asObject).flatMap(_.toMap.get(field)), d.timestamp))

                for {
                  params <- resolveMergeParams(mappings, rm)
                  res    <- runRootMerge(rm.copy(params = params), subFields)
                } yield field -> res
            }

            val defFields = dx.flatMap(_.json).flatMap(_.asObject).flatMap(_.toMap.keys).toSet -- subMerges.keySet

            val defDocs = defFields.toSeq.map { field =>
              val subFields =
                dx.map(d => Document(d.json.flatMap(_.asObject).flatMap(_.toMap.get(field)), d.timestamp))

              for {
                merge <- defMerge
                res   <- runRootMerge(merge, subFields)
              } yield field -> res
            }

            val ts = dx.map(_.timestamp).max
            (subDocs ++ defDocs).toList.sequence
              .map(dx => Document(dx.toMap.mapValues(_.json.getOrElse(Json.Null)).asJson.some, ts))
        }
      }

    merges
      .get("main")
      .fold(s"Merge main is not defined".asLeft[Document])(m => runMerge(m, Nil, documents))
  }
}

final case class Document(json: Option[Json], timestamp: Long)
