package io.replicant.hdml

import cats.syntax.option._
import io.circe.parser._

object Main {
  def main(args: Array[String]): Unit = {
    val program =
      """
        |func add(a, b): a + b
        |
        |func max(a, b):
        |  if (a < b) than b else a
        |
        |func complexSquare(x, y): {
        |  "width": max(x.width, y.width),
        |  "height": max(x.height, y.height),
        |  "square": max(x.width, y.width) * max(x.height, y.height)
        |}
        |
        |/* some
        |   multiline
        |   comment */
        |merge fooMerge:
        |  $.aggKey.$fold(0, add),
        |  $.maxKey.$fold(0, max),
        |  $.shape.$fold({"width": 0, "height": 0, "square": 0}, complexSquare)
        |
        |// entry point
        |merge main:
        |  $.foo.$fooMerge,
        |  $.timestampKey.$timestamp,
        |  $.*.$random
        |
      """.stripMargin

    val documents = Seq(
      Document(
        parse(
          """{
            |  "foo": {
            |     "aggKey": 2,
            |     "maxKey": 7,
            |     "shape": {
            |       "width": 3,
            |       "height": 1,
            |       "square": 3
            |     }
            |   },
            |   "timestampKey": "somestr",
            |   "defaultValue": [2, 4]
            |}
          """.stripMargin
        ).right.get.some,
        1
      ),
      Document(
        parse(
          """{
            |  "foo": {
            |     "aggKey": 4,
            |     "maxKey": 2,
            |     "shape": {
            |       "width": 3,
            |       "height": 2,
            |       "square": 6
            |     }
            |   }
            |}
          """.stripMargin
        ).right.get.some,
        2
      ),
      Document(
        parse(
          """{
            |  "foo": {
            |     "aggKey": 7,
            |     "maxKey": 1,
            |     "shape": {
            |       "width": 7,
            |       "height": 1,
            |       "square": 7
            |     }
            |   },
            |   "timestampKey": "newstr",
            |   "defaultValue": []
            |}
          """.stripMargin
        ).right.get.some,
        3
      )
    )

    val res = for {
      p <- Parser.parse(program)
      r <- Interpreter.run(p, documents)
    } yield r

    println(res)
//  Results in:
//    Right(Document(Some({
//      "foo" : {
//        "aggKey" : 13.0,
//        "maxKey" : 7.0,
//        "shape" : {
//          "width" : 7.0,
//          "height" : 2.0,
//          "square" : 14.0
//        }
//      },
//      "timestampKey" : "newstr",
//      "defaultValue" : null
//    }),3))
  }
}
