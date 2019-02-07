/**
  * Copyright 2015 Thomson Reuters
  *
  * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *   http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an “AS IS” BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  *
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package cmwell.ws.adt

import cmwell.fts._
import cmwell.util.collections._
import cmwell.ws.qp.Encoder
import cmwell.ws.util.{DateParser, FromDate, ToDate}
import wsutil._

import scala.language.reflectiveCalls
import scala.util.Try

sealed trait IteratorState {
  def pathFilter: Option[PathFilter]
  def fieldsFilter: Option[FieldFilter]
  def datesFilter: Option[DatesFilter]
  def paginationParams: PaginationParams
  def scrollTTL :Long
  def withHistory: Boolean
  def withDeleted: Boolean
  def debugInfo: Boolean
}

case class CreateIteratorState(pathFilter: Option[PathFilter],
                               fieldsFilter: Option[FieldFilter],
                               datesFilter: Option[DatesFilter],
                               paginationParams: PaginationParams,
                               scrollTTL :Long ,
                               withHistory: Boolean,
                               withDeleted: Boolean ,
                               debugInfo: Boolean)
    extends IteratorState

object CreateIteratorState {
  implicit val parser = IteratorState.parser.innerIteratorState
}

object IteratorState{


  val decodedCmwellScrollId = "cmwell-iterator"
  val encodedCmwelScrollId = "HAAAY213ZWxsLWl0ZXJhdG9y"
  def encode(cs: IteratorState): String = {
    val bare = format(cs)
    val bArr = cmwell.util.string.Zip.compress(bare)
    cmwell.util.string.Base64.encodeBase64URLSafeString(bArr)
  }

  def encodeCreateIteratorKey(str: String): String = {
    val bArr = cmwell.util.string.Zip.compress(str)
    cmwell.util.string.Base64.encodeBase64URLSafeString(bArr)
  }

  def format(cs: IteratorState): String = {
    val path = cs.pathFilter.fold("")(pathFilter => pathFilter.path)
    val descendants = cs.pathFilter.fold("")(pathFilter => if (pathFilter.descendants) "|e" else "")
    val from = cs.datesFilter.fold("")(datesFilter => datesFilter.from.fold("")(fromDate=> "|" + fromDate.toString))
    val to = cs.datesFilter.fold("")(datesFilter => datesFilter.to.fold("")(toDate=> "|" + toDate.toString))
    val fieldsFilter = cs.fieldsFilter.fold("")(ff => "|" + Encoder.encodeFieldFilter(ff))
    val datesFilter = cs.datesFilter.fold("")(dates => dates.from.fold("")("|" + _) + dates.to.fold("")("|" + _))
    val paginationParams = "|" + cs.paginationParams.offset
    val length = "|" + cs.paginationParams.length
    val offset = "|" + cs.paginationParams.offset
    val scrollTTL = "|" + cs.scrollTTL
    val withHistory = if (cs.withHistory) "|h" else ""
    val withDeleted = if (cs.withDeleted) "|d" else ""
    val debugInfo = if (cs.debugInfo) "|i" else ""
    path + descendants + from + to + offset + length + scrollTTL + withHistory + withDeleted + debugInfo + fieldsFilter
  }

  def decode[T <: IteratorState: parser.Parser](base64: String): Try[T] = {
    if (base64.isEmpty) scala.util.Failure(new IllegalArgumentException("scroll id cannot be empty"))
    else
      Try {
        val bArr = cmwell.util.string.Base64.decodeBase64(base64)
        cmwell.util.string.Zip.decompress(bArr)
      }.flatMap(parse[T])
  }

  def decodeCreateIteratorKey(str:String) = {
    val bArr = cmwell.util.string.Base64.decodeBase64(str)
    cmwell.util.string.Zip.decompress(bArr)
  }

  def parse[T <: IteratorState: parser.Parser](s: String): Try[T] = {
    import parser._
    val p: Parser[T] = implicitly[parser.Parser[T]]
    parser.parseAll(p, s) match {
      case NoSuccess(msg, _) => scala.util.Failure(new IllegalArgumentException(msg))
      case Success(res, _) =>
        res.fieldsFilter.filter(fieldFilterHasKey("system.indexTime")).fold[Try[T]](scala.util.Success(res)) { _ =>
          scala.util.Failure(new IllegalArgumentException("create iterator API's qp must not contain \"system.indexTime\""))
        }
    }
  }

  def fieldFilterHasKey(key: String)(fieldFilter: FieldFilter): Boolean = fieldFilter match {
    case SingleFieldFilter(_, _, fieldName, _) => fieldName == key
    case MultiFieldFilter(_, fieldFilters)     => fieldFilters.exists(fieldFilterHasKey(key))
  }

  val parser = new cmwell.ws.util.FieldFilterParser {

    object Inner {

      import scala.util.{Failure => F, Success => S}

      private[this] def extractRawKeyAsFieldName(rff: RawFieldFilter): Try[FieldFilter] = rff match {
        case RawMultiFieldFilter(fo, rffs)               => Try.traverse(rffs)(extractRawKeyAsFieldName).map(MultiFieldFilter(fo, _))
        case RawSingleFieldFilter(fo, vo, Right(dfk), v) => S(SingleFieldFilter(fo, vo, dfk.internalKey, v))
        case UnevaluatedQuadFilter(_, _, alias) =>
          F {
            new IllegalArgumentException(
              s"supplied fields must be direct. system.quad with alias[$alias] is not direct and needs to be resolved (use fully qualified URI instead)."
            )
          }
        case RawSingleFieldFilter(fo, vo, Left(rfk), v) =>
          F {
            new IllegalArgumentException(s"supplied fields must be direct. ${rfk.externalKey} needs to be resolved.")
          }
      }

      private[this] def charBoolean(c: Char): Parser[Boolean] = opt(s"|$c") ^^ {
        _.isDefined
      }

      val long: Parser[Long] = "|" ~> wholeNumber ^^ {
        _.toLong
      }


      val int: Parser[Int] =  "|" ~> wholeNumber ^^ {
        _.toInt
      }
      val page /*: Parser[(Long,Option[Long])]*/ = "|" ~> int ~ ("," ~> int).?
      val path: Parser[Option[String]] = opt("/[^|$]*".r)
      val shortDate: Parser[Option[String]] = opt("|" ~> "\\d{4}-\\d{2}-\\d{2}".r)
      val fullDate: Parser[Option[String]] = opt("|" ~> "(\\d{4})-(\\d{2})-(\\d{2})[T](\\d{2})[:](\\d{2})[:](\\d{2})[.](\\d{2,3})[Z]".r)
      val longDate: Parser[Option[String]] = opt("|" ~> "(\\d{4})-(\\d{2})-(\\d{2})[ ](\\d{2})[:](\\d{2})[:](\\d{2})".r)
      val h: Parser[Boolean] = charBoolean('h')
      val d: Parser[Boolean] = charBoolean('d')
      val i: Parser[Boolean] = charBoolean('i')
      val e: Parser[Boolean] = charBoolean('e')
      val qp: Parser[Try[FieldFilter]] = "|" ~> unwrappedFieldFilters.^^(extractRawKeyAsFieldName)
      val ff: Parser[Option[FieldFilter]] = opt(qp ^? ({
        case S(fieldFilter) => fieldFilter
      }, f => f.failed.get.getMessage))

    }

    import Inner._

    val innerIteratorState =  path ~ e ~ (shortDate | fullDate | longDate) ~ (shortDate | fullDate | longDate)~ int ~ int ~ long  ~ h ~ d ~ i ~ ff ^^ {
      case  path ~ descendants ~ from ~ to ~ offset ~ length ~ scrollTTL ~ withHistory ~ withDeleted ~ debugInfo ~ fieldsFilter =>
        CreateIteratorState(Some(PathFilter(path.get, descendants)),
          fieldsFilter,
          Some(DatesFilter(DateParser.parseDate(from.getOrElse(""), FromDate).toOption, DateParser.parseDate(to.getOrElse(""), ToDate).toOption)),
          PaginationParams(offset,length),
          scrollTTL ,
          withHistory,
          withDeleted ,
          debugInfo)
    }

  }

}


//date option - define the regex well - test it
//remove logs
//extract cmwell-iterator to variable
//remove non relevant checks here on qp
//manual testing
