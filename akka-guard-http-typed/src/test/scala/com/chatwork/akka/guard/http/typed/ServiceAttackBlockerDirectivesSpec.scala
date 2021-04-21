package com.chatwork.akka.guard.http.typed

import akka.actor.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.{ ActorRef, Scheduler }
import akka.http.scaladsl.model.{ HttpResponse, StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.{ Timeout => AkkaTimeout }
import com.chatwork.akka.guard.typed.SABActor
import com.chatwork.akka.guard.typed.SABActor.SABStatus
import com.chatwork.akka.guard.typed.config.{ LinealBackoff, SABConfig }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.duration._
import scala.util.{ Success, Try }

class ServiceAttackBlockerDirectivesSpec
    extends AnyFreeSpec
    with BeforeAndAfterAll
    with Matchers
    with ScalatestRouteTest
    with ScalaFutures
    with Eventually {

  val testTimeFactor: Int = sys.env.getOrElse("TEST_TIME_FACTOR", "1").toInt

  val testKit: ActorTestKit = ActorTestKit()

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(
      timeout = scaled(Span(2 * testTimeFactor, Seconds)),
      interval = scaled(Span(5 * testTimeFactor, Millis))
    )

  override protected def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  implicit val timeout: AkkaTimeout = AkkaTimeout(4.seconds)
  val clientId                      = "id-1"
  val uri: String => String         = prefix => s"/$prefix/$clientId"

  "ServiceAttackBlockerDirectivesSpec typed" - {
    "Success" in new WithFixture {

      (1 to 10).foreach { _ =>
        Get(uri(ok)) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }
      }

      import akka.actor.typed.scaladsl.AskPattern._

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert(messageRef.?(SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Closed)
        }
      }

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert(messageRef.?(SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Closed)
        }
      }

      (1 to 10).foreach { _ =>
        Get(uri(bad)) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert(messageRef.?(SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Open)
        }
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
    implicit val scheduler: Scheduler   = testKit.system.scheduler
    val myBlocker: String => Directive0 = serviceAttackBlocker(blocker)

    def invokeMessageRef(messageRef: ActorRef[SABActor.Command] => Unit): Unit = {
      val probe = testKit.createTestProbe[Receptionist.Listing]()
      testKit.system.receptionist ! Receptionist.Subscribe(SABActor.SABActorServiceKey, probe.ref)
      probe
        .receiveMessage((5 * testTimeFactor).seconds).allServiceInstances(SABActor.SABActorServiceKey).foreach(
          messageRef
        )
    }

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
