package com.chatwork.akka.guard.http

import akka.actor.{ ActorPath, ActorSelection }
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.pattern.ask
import akka.util.Timeout
import com.chatwork.akka.guard.{ BFABlockerActor, BFABlockerStatus, BFABrokerConfig }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FreeSpec, Matchers }

import scala.concurrent.duration._
import scala.util.Failure

class BFABlockerActorDirectivesSpec extends FreeSpec with Matchers with ScalatestRouteTest with ScalaFutures {

  implicit val timeout: Timeout = Timeout(4.second)
  val clientId                  = "id-1"
  val uri: String => String     = prefix => s"/$prefix/$clientId"

  "BFABlockerDirectivesSpec" - {
    "Success" in new WithFixture {

      (1 to 10).foreach { _ =>
        Get(uri(ok)) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }
      }

      messageRef
        .?(BFABlockerActor.GetStatus)
        .mapTo[BFABlockerStatus]
        .futureValue shouldBe BFABlockerStatus.Closed

      (1 to 10).foreach { _ =>
        Get(uri(bad)) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }

      messageRef
        .?(BFABlockerActor.GetStatus)
        .mapTo[BFABlockerStatus]
        .futureValue shouldBe BFABlockerStatus.Open

      (1 to 10).foreach { _ =>
        Get(uri(bad)) ~> routes ~> check {
          status shouldBe StatusCodes.InternalServerError
        }
      }

    }
  }

  val rejectionHandler: RejectionHandler =
    RejectionHandler.default

  trait WithFixture {
    import BFABlockerDirectives._

    val bfaConfig: BFABrokerConfig[Unit, RouteResult] =
      BFABrokerConfig(
        maxFailures = 9,
        failureTimeout = 10.seconds,
        resetTimeout = 1.hour,
        failedResponse = Failure(new Exception("failed!!")),
        isFailed = {
          case RouteResult.Complete(res) if res.status == StatusCodes.OK => false
          case RouteResult.Rejected(rejections)                          => rejectionHandler(rejections).isDefined
          case _                                                         => true
        }
      )

    val blocker: BFABlocker             = BFABlocker(system, bfaConfig)
    val myBlocker: String => Directive0 = bfaBlocker(blocker)

    val messagePath: ActorPath     = system / blocker.actorName / BFABlockerActor.name(clientId)
    val messageRef: ActorSelection = system.actorSelection(messagePath)

    val ok  = "ok"
    val bad = "bad"
    val rej = "reject"
    val routes: Route =
      get {
        path(ok / Segment) { id =>
          myBlocker(id) {
            complete("index")
          }
        } ~
        path(bad / Segment) { id =>
          myBlocker(id) {
            complete(HttpResponse(StatusCodes.BadRequest))
          }
        } ~
        path(rej / Segment) { id =>
          myBlocker(id) {
            reject(ValidationRejection("hoge"))
          }
        }
      }
  }

}
