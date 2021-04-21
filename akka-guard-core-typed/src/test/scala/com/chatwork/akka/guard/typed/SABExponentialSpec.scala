package com.chatwork.akka.guard.typed

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.Behaviors
import akka.util.{ Timeout => AkkaTimeout }
import com.chatwork.akka.guard.typed.SABActor.{ BecameClosed, SABMessage, SABStatus }
import com.chatwork.akka.guard.typed.SABBroker.SABBrokerMessage
import com.chatwork.akka.guard.typed.config.{ ExponentialBackoff, LinealBackoff, SABConfig }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Try }

class SABExponentialSpec
    extends AnyFreeSpecLike
    with BeforeAndAfterAll
    with ScalaCheckPropertyChecks
    with Matchers
    with ScalaFutures
    with Eventually {

  val testKit: ActorTestKit = ActorTestKit()

  val testTimeFactor: Int = sys.env.getOrElse("TEST_TIME_FACTOR", "1").toInt

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(
      timeout = scaled(Span(2 * testTimeFactor, Seconds)),
      interval = scaled(Span(5 * testTimeFactor, Millis))
    )

  override protected def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  type T = String
  type R = String

  val BoundaryLength              = 50
  val failedMessage               = "failed!!"
  val errorMessage                = "error!!"
  val successMessage              = "success!!"
  val failedResponse: Try[String] = Failure(new Exception(failedMessage))
  val isFailed: String => Boolean = _ => false

  "SABExponential typed" - {
    "auto reset" in {
      import testKit.system
      implicit val timeout: AkkaTimeout = AkkaTimeout((5 * testTimeFactor).seconds)
      val sabBrokerName1: String        = "broker-1"
      val messageId: String             = "id-1"
      val config: SABConfig = SABConfig(
        maxFailures = 9,
        failureDuration = 10.seconds,
        backoff = ExponentialBackoff(
          minBackoff = (2 * testTimeFactor).seconds,
          maxBackoff = (10 * testTimeFactor).seconds,
          randomFactor = 0.2
        )
      )
      val handler: String => Future[String] = {
        case request if request.length < BoundaryLength  => Future.failed(new Exception(errorMessage))
        case request if request.length >= BoundaryLength => Future.successful(successMessage)
      }
      val testProbe = testKit.createTestProbe[SABActor.Command]()

      val sabBrokerBehavior = SABBroker(
        SABSupervisor(config) { (message: SABMessage[T, R]) =>
          config.backoff match {
            case b: ExponentialBackoff =>
              Behaviors.setup { implicit context =>
                Behaviors.withTimers { implicit timers =>
                  val actor = new SABActor.ExponentialBackoffActor[T, R](
                    message.id,
                    maxFailures = config.maxFailures,
                    backoff = b,
                    failureTimeout = config.failureDuration,
                    failedResponse,
                    isFailed,
                    eventHandler = None
                  ) {
                    override protected def createScheduler(delay: FiniteDuration, attempt: Long)(key: Any): Unit = {
                      testProbe.ref ! BecameClosed(attempt, 0, setTimer = true)
                    }
                  }
                  actor.behavior
                }
              }
            case _: LinealBackoff => fail()
          }
        }
      )
      val sabBroker = testKit.spawn(sabBrokerBehavior, sabBrokerName1)

      def createMessage(value: String): ActorRef[Try[R]] => SABBrokerMessage[T, R] =
        reply => SABBrokerMessage(SABMessage(messageId, value, handler, reply))

      def invokeMessageRef(messageRef: ActorRef[SABActor.Command] => Unit): Unit = {
        val probe = testKit.createTestProbe[Receptionist.Listing]()
        testKit.system.receptionist ! Receptionist.Subscribe(SABActor.SABActorServiceKey, probe.ref)
        probe
          .receiveMessage((5 * testTimeFactor).seconds).allServiceInstances(SABActor.SABActorServiceKey).foreach(
            messageRef
          )
      }

      import akka.actor.typed.scaladsl.AskPattern._
      val message1 = createMessage("A" * 50)
      (sabBroker ? message1).mapTo[String].futureValue shouldBe successMessage

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert((messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Closed)
        }
      }

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert(
            messageRef
              .ask[SABActor.GetAttemptResponse](SABActor.GetAttemptRequest(messageId, _)).futureValue.attempt === 0
          )
        }
      }

      val message2 = createMessage("A" * 49)
      for { _ <- 1 to 10 } (sabBroker ? message2).mapTo[String].failed.futureValue

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert((messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Open)
        }
      }

      testProbe.expectMessage(BecameClosed(1, 0, setTimer = true))
      invokeMessageRef { messageRef =>
        messageRef ! BecameClosed(1, 0, setTimer = true)
      }

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert(
            messageRef
              .ask[SABActor.GetAttemptResponse](SABActor.GetAttemptRequest(messageId, _)).futureValue.attempt === 1
          )
        }
      }

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert((messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Closed)
        }
      }

      for { _ <- 1 to 10 } (sabBroker ? message2).mapTo[String].failed.futureValue

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert((messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Open)
        }
      }

      testProbe.expectMessage(BecameClosed(2, 0, setTimer = true))
      invokeMessageRef { messageRef =>
        messageRef ! BecameClosed(2, 0, setTimer = true)
      }

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert(
            messageRef
              .ask[SABActor.GetAttemptResponse](SABActor.GetAttemptRequest(messageId, _)).futureValue.attempt === 2
          )
        }
      }

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert((messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Closed)
        }
      }

      for { _ <- 1 to 10 } (sabBroker ? message2).mapTo[String].failed.futureValue

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert((messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Open)
        }
      }

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert(
            messageRef
              .ask[SABActor.GetAttemptResponse](SABActor.GetAttemptRequest(messageId, _)).futureValue.attempt === 3
          )
        }
      }

      testProbe.expectMessage(BecameClosed(0, 0, setTimer = true))
      invokeMessageRef { messageRef =>
        messageRef ! BecameClosed(0, 0, setTimer = true)
      }

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert((messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Closed)
        }
      }

      for { _ <- 1 to 10 } (sabBroker ? message2).mapTo[String].failed.futureValue

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert((messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Open)
        }
      }

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert(
            messageRef
              .ask[SABActor.GetAttemptResponse](SABActor.GetAttemptRequest(messageId, _)).futureValue.attempt === 1
          )
        }
      }

      testProbe.expectMessage(BecameClosed(1, 0, setTimer = true))
      invokeMessageRef { messageRef =>
        messageRef ! BecameClosed(1, 0, setTimer = true)
      }

      eventually(Timeout(Span.Max)) {
        invokeMessageRef { messageRef =>
          assert((messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue === SABStatus.Closed)
        }
      }
    }
  }
}
