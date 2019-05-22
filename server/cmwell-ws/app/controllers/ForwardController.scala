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
package controllers

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpHeader, HttpRequest}
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging
import javax.inject._
import play.api.mvc._

import scala.concurrent._

@Singleton
class ForwardController @Inject()(components: ControllerComponents)(implicit ec: ExecutionContext)
  extends AbstractController(components) with LazyLogging {

  private implicit val sys: ActorSystem = ActorSystem("fw-ctrlr")
  private implicit val mat: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(sys))

  //TODO if originalResponse is chunked (or binary), we should do `Ok.chunked(originalResp.entity.dataBytes)`.
  //TODO Otherwise, if originalResponse is not chunked and is textual - we should L1L2 using url as key and .utf8string as value.
  //TODO Moreover: support all response statuses, if originalResp is 301 we should also return 301. Support query parameters and request headers forwarding...

  def handleGet(host: String, path: String): Action[AnyContent] = Action.async { implicit req =>
    val url = s"${if (host.startsWith("http")) host else s"http://$host"}/$path"
    Http().singleRequest(HttpRequest(uri = url)).flatMap { originalResp =>
      val contentType = originalResp.entity.contentType.toString()
      byteStringSourceToString(originalResp.entity.dataBytes).
        map(payload => addHeaders(originalResp.headers, Ok(payload)).as(contentType))
    }
  }

  private def byteStringSourceToString(bs: Source[ByteString, Any]): Future[String] = bs.runFold(ByteString.empty)(_++_).map(_.utf8String)
  private def addHeaders(headers: Seq[HttpHeader], result: Result): Result = {
    logger.info("Response Headers:" + headers.mkString(","))
    headers.foldLeft(result) { (r, h) => r.withHeaders(h.name() -> h.value()) }
  }
}
