package com.chatwork.akka.guard.http

import akka.actor.{ ActorPath, ActorSelection }
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.pattern.ask
import akka.util.Timeout
import com.chatwork.akka.guard.{ SABBrokerConfig, ServiceAttackBlockerActor, ServiceAttackBlockerStatus }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FreeSpec, Matchers }

import scala.concurrent.duration._
import scala.util.{ Failure, Try }

class ServiceAttackBlockerDirectivesSpec extends FreeSpec with Matchers with ScalatestRouteTest with ScalaFutures {

  implicit val timeout: Timeout = Timeout(4.second)
  val clientId                  = "id-1"
  val uri: String => String     = prefix => s"/$prefix/$clientId"

  "ServiceAttackBlockerDirectivesSpec" - {
    "Success" in new WithFixture {

      (1 to 10).foreach { _ =>
        Get(uri(ok)) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }
      }

      messageRef
        .?(ServiceAttackBlockerActor.GetStatus)
        .mapTo[ServiceAttackBlockerStatus]
        .futureValue shouldBe ServiceAttackBlockerStatus.Closed

      (1 to 10).foreach { _ =>
        Get(uri(bad)) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }

      messageRef
        .?(ServiceAttackBlockerActor.GetStatus)
        .mapTo[ServiceAttackBlockerStatus]
        .futureValue shouldBe ServiceAttackBlockerStatus.Open

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
    import ServiceAttackBlockerDirectives._

    val failedResponse: Try[RouteResult] = Failure(new Exception("failed!!"))
    val isFailed: RouteResult => Boolean = {
      case RouteResult.Complete(res) if res.status == StatusCodes.OK => false
      case RouteResult.Rejected(rejections)                          => rejectionHandler(rejections).isDefined
      case _                                                         => true
    }

    val sabConfig: SABBrokerConfig =
      SABBrokerConfig(
        maxFailures = 9,
        failureTimeout = 10.seconds,
        resetTimeout = 1.hour
      )

    val blocker: ServiceAttackBlocker   = ServiceAttackBlocker(system, sabConfig)(failedResponse, isFailed)
    val myBlocker: String => Directive0 = serviceAttackBlocker(blocker)

    val messagePath: ActorPath     = system / blocker.actorName / ServiceAttackBlockerActor.name(clientId)
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
