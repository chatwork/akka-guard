package com.chatwork.akka.guard.http

import akka.actor.{ ActorPath, ActorSelection, ActorSystem }
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.pattern.ask
import akka.util.Timeout
import com.chatwork.akka.guard.{ BFABlocker, BFABlockerStatus, BFABrokerConfig }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FreeSpec, Matchers }

import scala.concurrent.duration._
import scala.util.Failure

class BFABlockerDirectivesSpec extends FreeSpec with Matchers with ScalatestRouteTest with ScalaFutures {

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
        .?(BFABlocker.GetStatus)
        .mapTo[BFABlockerStatus]
        .futureValue shouldBe BFABlockerStatus.Closed

      (1 to 10).foreach { _ =>
        Get(uri(bad)) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }

      messageRef
        .?(BFABlocker.GetStatus)
        .mapTo[BFABlockerStatus]
        .futureValue shouldBe BFABlockerStatus.Open

      (1 to 10).foreach { _ =>
        Get(uri(bad)) ~> routes ~> check {
          status shouldBe StatusCodes.InternalServerError
        }
      }

    }
  }

  trait WithFixture extends BFABlockerDirectives {
    override protected val bfaActorSystem: ActorSystem = system
    override protected val bfaConfig: BFABrokerConfig[Unit, RouteResult] =
      BFABrokerConfig(
        maxFailures = 9,
        failureTimeout = 10.seconds,
        resetTimeout = 1.hour,
        failedResponse = Failure(new Exception("failed!!")),
        isFailed = {
          case RouteResult.Complete(response) if response.status == StatusCodes.OK => false
          case RouteResult.Rejected(rejections)                                    => true
          case _                                                                   => true
        }
      )
    val messagePath: ActorPath     = system / bfaActorName / BFABlocker.name(clientId)
    val messageRef: ActorSelection = system.actorSelection(messagePath)

    val ok  = "ok"
    val bad = "bad"
    val routes: Route =
      get {
        path(ok / Segment) { id =>
          bfaBlocker(id) {
            complete("index")
          }
        } ~
        path(bad / Segment) { id =>
          bfaBlocker(id) {
            complete(HttpResponse(StatusCodes.BadRequest))
          }
        }
      }
  }

}
