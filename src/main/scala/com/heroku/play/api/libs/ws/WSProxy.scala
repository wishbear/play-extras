package com.heroku.play.api.libs.ws

import play.api.libs.iteratee.{ Iteratee, Concurrent, Enumerator }
import com.ning.http.client._
import com.ning.http.client.AsyncHandler.STATE
import play.api.mvc._
import play.api.libs.ws.WS
import concurrent.{ Future, Promise, future, promise }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.ResponseHeader
import com.ning.http.client.Request
import play.api.mvc.SimpleResult
import play.api.http.Status

object WSProxy extends Controller {

  def proxyGetAsync(url: String, responseHeadersToOverwrite: (String, String)*) = proxyRequestAsync(WS.client.prepareGet(url).build(), responseHeadersToOverwrite.toMap)

  def proxyGetAsyncAuthenticated(url: String, authHeaderValue: String, responseHeadersToOverwrite: (String, String)*) = proxyRequestAsync(WS.client.prepareGet(url).addHeader("AUTHORIZATION", authHeaderValue).build(), responseHeadersToOverwrite.toMap)

  def proxyRequestAsync(req: Request, responseHeadersToOverwrite: Map[String, String] = Map.empty): Future[Result] = {
    val enum = Enumerator.imperative[Array[Byte]]()
    val headers = promise[HttpResponseHeaders]()
    val status = promise[Int]()

    WS.client.executeRequest(req, new AsyncHandler[Unit] {
      def onThrowable(p1: Throwable) {
      }

      def onBodyPartReceived(part: HttpResponseBodyPart): STATE = {
        while (!enum.push(part.getBodyPartBytes)) {
          Thread.sleep(10)
        }
        STATE.CONTINUE
      }

      def onStatusReceived(s: HttpResponseStatus): STATE = {
        status.success(s.getStatusCode)
        STATE.CONTINUE
      }

      def onHeadersReceived(h: HttpResponseHeaders): STATE = {
        headers.success(h)
        if (h.getHeaders.containsKey("Content-Length") && h.getHeaders.get("Content-Length").get(0) != "0") {
          STATE.CONTINUE
        } else {
          STATE.ABORT
        }
      }

      def onCompleted() {
        enum.close()
      }
    })

    import collection.JavaConverters._

    status.future.flatMap {
      s =>
        headers.future.map {
          h =>
            val hmap = h.getHeaders.iterator().asScala.map {
              entry => entry.getKey -> entry.getValue.get(0)
            }.toMap ++ responseHeadersToOverwrite
            if (h.getHeaders.containsKey("transfer-encoding") && h.getHeaders.get("transfer-encoding").get(0) == "chunked") {
              Status(s).stream(enum).withHeaders(hmap.toSeq: _*)
            } else if (h.getHeaders.containsKey("Content-Length") && h.getHeaders.get("Content-Length").get(0) != "0") {
              SimpleResult(ResponseHeader(s, hmap), enum)
            } else {
              SimpleResult(ResponseHeader(s, hmap), Enumerator(Results.EmptyContent()))
            }
        }
    }

  }

}
