package com.chatwork.akka.guard.typed

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.receptionist.Receptionist
import akka.util.Timeout
import com.chatwork.akka.guard.typed.SABActor.{ GetStatus, SABMessage, SABStatus }
import com.chatwork.akka.guard.typed.SABBroker.SABBrokerMessage
import com.chatwork.akka.guard.typed.SABSupervisor.SABSupervisorMessage
import com.chatwork.akka.guard.typed.config.{ ExponentialBackoff, SABConfig }
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Try }

class SABReceiveTimeoutSpec
    extends AnyFreeSpecLike
    with BeforeAndAfterAll
    with ScalaCheckPropertyChecks
    with Matchers
    with ScalaFutures {
  val testKit: ActorTestKit = ActorTestKit()

  override implicit def patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(5, Millis))

  override protected def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  type T = String
  type R = String

  val BoundaryLength           = 50
  val genShortStr: Gen[String] = Gen.asciiStr.suchThat(_.length < BoundaryLength)
  val genLongStr: Gen[String]  = Gen.asciiStr.suchThat(_.length >= BoundaryLength)

  val failedMessage               = "failed!!"
  val errorMessage                = "error!!"
  val successMessage              = "success!!"
  val failedResponse: Try[R]      = Failure(new Exception(failedMessage))
  val isFailed: String => Boolean = _ => false

  "SABReceiveTimeout typed" - {
    "receive timeout" in {
      import testKit.system
      implicit val timeout: Timeout = Timeout(5 seconds)
      val sabBrokerName1: String    = "broker-1"
      val messageId: String         = "id-1"
      val config: SABConfig = SABConfig(
        maxFailures = 9,
        failureDuration = 10.seconds,
        backoff = ExponentialBackoff(minBackoff = 1 seconds, maxBackoff = 5 seconds, randomFactor = 0.2),
        guardResetTimeout = Some(3 seconds)
      )
      val handler: T => Future[R] = {
        case request if request.length < BoundaryLength  => Future.failed(new Exception(errorMessage))
        case request if request.length >= BoundaryLength => Future.successful(successMessage)
      }
      val sabBrokerBehavior                      = SABBroker(config, failedResponse, isFailed)
      val sabBroker: ActorRef[SABBroker.Command] = testKit.spawn(sabBrokerBehavior, sabBrokerName1)

      def createMessage(value: String): ActorRef[Try[R]] => SABBrokerMessage[T, R] =
        reply => SABBrokerMessage(SABSupervisorMessage(SABMessage(messageId, value, handler, reply)))

      import akka.actor.typed.scaladsl.AskPattern._

      val message1 = createMessage("A" * 50)
      sabBroker.ask[Try[String]](message1(_)).futureValue shouldBe successMessage

      Thread.sleep(1000 * 5)

      sabBroker.ask[Try[String]](message1(_)).futureValue shouldBe successMessage

      val probe1 = testKit.createTestProbe[Receptionist.Listing]
      testKit.system.receptionist ! Receptionist.Subscribe(SABActor.SABActorServiceKey, probe1.ref)
      probe1.receiveMessage().allServiceInstances(SABActor.SABActorServiceKey).foreach { actorRef =>
        actorRef.ask[SABActor.SABStatus](reply => GetStatus(reply)).futureValue shouldBe SABStatus.Closed
      }

      val message2 = createMessage("A" * 49)
      for { _ <- 1 to 10 } (sabBroker ? message2).mapTo[String].failed.futureValue

      val probe2 = testKit.createTestProbe[Receptionist.Listing]
      testKit.system.receptionist ! Receptionist.Subscribe(SABActor.SABActorServiceKey, probe2.ref)
      probe2.receiveMessage().allServiceInstances(SABActor.SABActorServiceKey).foreach { actorRef =>
        actorRef.ask[SABActor.SABStatus](reply => GetStatus(reply)).futureValue shouldBe SABStatus.Open
      }

      val probe3 = testKit.createTestProbe[Receptionist.Listing]
      testKit.system.receptionist ! Receptionist.Subscribe(SABActor.SABActorServiceKey, probe3.ref)
      probe3.receiveMessage().allServiceInstances(SABActor.SABActorServiceKey).foreach { actorRef =>
        actorRef
          .ask[SABActor.GetAttemptResponse](reply =>
            SABActor.GetAttemptRequest(messageId, reply)
          ).futureValue.attempt shouldBe 1
      }

    }
  }
}
