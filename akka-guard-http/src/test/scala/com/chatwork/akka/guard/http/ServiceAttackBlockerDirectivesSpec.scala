package com.chatwork.akka.guard.http

import akka.actor.{ ActorPath, ActorSelection }
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.pattern.ask
import akka.util.{ Timeout => AkkaTimeout }
import com.chatwork.akka.guard._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.duration._
import scala.util.{ Success, Try }

class ServiceAttackBlockerDirectivesSpec
    extends AnyFreeSpec
    with Matchers
    with ScalatestRouteTest
    with ScalaFutures
    with Eventually {

  val testTimeFactor: Int = sys.env.getOrElse("TEST_TIME_FACTOR", "1").toInt

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(
      timeout = scaled(Span(3 * testTimeFactor, Seconds)),
      interval = scaled(Span(1 * testTimeFactor, Millis))
    )

  implicit val timeout: AkkaTimeout = AkkaTimeout((4 * testTimeFactor).seconds)
  val clientId                      = "id-1"
  val uri: String => String         = prefix => s"/$prefix/$clientId"

  "ServiceAttackBlockerDirectivesSpec untyped" - {
    "Success" in new WithFixture {

      (1 to 10).foreach { _ =>
        Get(uri(ok)) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }
      }

      eventually(Timeout((15 * testTimeFactor).seconds)) {
        messageRef
          .?(SABActor.GetStatus)
          .mapTo[SABStatus]
          .futureValue == SABStatus.Closed
      }

      (1 to 10).foreach { _ =>
        Get(uri(bad)) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }

      eventually(Timeout((15 * testTimeFactor).seconds)) {
        messageRef
          .?(SABActor.GetStatus)
          .mapTo[SABStatus]
          .futureValue == SABStatus.Open
      }

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

    val failedResponse: Try[RouteResult] = Success(RouteResult.Complete(HttpResponse(StatusCodes.InternalServerError)))

    val isFailed: RouteResult => Boolean = {
      case RouteResult.Complete(res) if res.status == StatusCodes.OK => false
      case RouteResult.Rejected(rejections)                          => rejectionHandler(rejections).isDefined
      case _                                                         => true
    }

    val sabConfig: SABConfig =
      SABConfig(
        maxFailures = 9,
        failureDuration = (10 * testTimeFactor).seconds,
        backoff = LinealBackoff((1 * testTimeFactor).hour)
      )

    val blocker: ServiceAttackBlocker   = ServiceAttackBlocker(system, sabConfig)(failedResponse, isFailed)
    val myBlocker: String => Directive0 = serviceAttackBlocker(blocker)

    val messagePath: ActorPath     = system / blocker.actorName / SABSupervisor.name(clientId) / SABActor.name(clientId)
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
