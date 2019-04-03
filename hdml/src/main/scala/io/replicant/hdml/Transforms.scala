package io.replicant.hdml

import io.circe.Json
import io.circe.syntax._
import io.replicant.hdml.Ast.{Literal, Value}

object Transforms {
  def fromJson(json: Json): Literal = () match {
    case _ if json.isNull    => Literal.Null
    case _ if json.isString  => Literal.String(json.asString.get)
    case _ if json.isBoolean => Literal.Boolean(json.asBoolean.get)
    case _ if json.isNumber  => Literal.Number(json.asNumber.get.toDouble)
    case _ if json.isArray   => Literal.Array(json.asArray.get.map(fromJson))
    case _ if json.isObject  => Literal.Object(json.asObject.get.toMap.mapValues(fromJson).toSeq)
  }

  def toJson(value: Value): Json = value match {
    case Literal.Null           => Json.Null
    case Literal.String(value)  => value.asJson
    case Literal.Boolean(value) => value.asJson
    case Literal.Number(value)  => value.asJson
    case Literal.Array(value)   => value.map(toJson).asJson
    case Literal.Object(value)  => value.toMap.mapValues(toJson).asJson
    case _                      => throw new IllegalArgumentException("Only literals may be converted to json")
  }
}
