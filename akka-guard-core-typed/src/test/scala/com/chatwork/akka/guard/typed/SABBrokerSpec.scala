package com.chatwork.akka.guard.typed

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.receptionist.Receptionist
import akka.util.Timeout
import com.chatwork.akka.guard.typed.SABActor.{ GetStatus, SABMessage, SABStatus }
import com.chatwork.akka.guard.typed.SABBroker.SABBrokerMessage
import com.chatwork.akka.guard.typed.config.{ LinealBackoff, SABConfig }
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.featurespec.AnyFeatureSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.{ BeforeAndAfterAll, GivenWhenThen }
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

class SABBrokerSpec
    extends AnyFeatureSpecLike
    with BeforeAndAfterAll
    with GivenWhenThen
    with ScalaCheckPropertyChecks
    with Matchers
    with ScalaFutures {
  val testKit: ActorTestKit = ActorTestKit()

  override implicit def patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(5, Millis))

  override protected def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  val BoundaryLength           = 50
  val genShortStr: Gen[String] = Gen.asciiStr.suchThat(_.length < BoundaryLength)
  val genLongStr: Gen[String]  = Gen.asciiStr.suchThat(_.length >= BoundaryLength)

  val failedMessage               = "failed!!"
  val errorMessage                = "error!!"
  val successMessage              = "success!!"
  val failedResponse: Try[String] = Failure(new Exception(failedMessage))
  val isFailed: String => Boolean = _ => false

  Feature("SABBrokerSpec typed") {

    Scenario("Success in LinealBackoff") {

      type T = String
      type R = String

      Given("broker pattern 1")
      import testKit.system
      implicit val timeout: Timeout = Timeout(5.seconds)
      import akka.actor.typed.scaladsl.AskPattern._

      val sabBrokerName1: String = "broker-1"
      val messageId: String      = "id-1"
      val config: SABConfig = SABConfig(
        maxFailures = 9,
        failureDuration = 10.seconds,
        backoff = LinealBackoff(1.hour)
      )
      val handler: T => Future[R] = {
        case request if request.length < BoundaryLength  => Future.failed(new Exception(errorMessage))
        case request if request.length >= BoundaryLength => Future.successful(successMessage)
      }
      val sabBrokerBehavior                      = SABBroker(config, failedResponse, isFailed)
      val sabBroker: ActorRef[SABBroker.Command] = testKit.spawn(sabBrokerBehavior, sabBrokerName1)

      def createMessage(value: String): ActorRef[Try[R]] => SABBrokerMessage[T, R] =
        reply => SABBrokerMessage(SABMessage(messageId, value, handler, reply))

      When("Long input")
      Then("return success message")
      forAll(genLongStr) { value =>
        sabBroker.ask[Try[String]](createMessage(value)(_)).futureValue shouldBe successMessage
      }

      And("Status Closed")
      val probe1 = testKit.createTestProbe[Receptionist.Listing]()
      testKit.system.receptionist ! Receptionist.Subscribe(SABActor.SABActorServiceKey, probe1.ref)
      probe1.receiveMessage().allServiceInstances(SABActor.SABActorServiceKey).foreach { actorRef =>
        actorRef.ask[SABActor.SABStatus](reply => GetStatus(reply)).futureValue shouldBe SABStatus.Closed
      }

      When("Short input")
      Then("return error message")
      forAll(genShortStr) { value =>
        sabBroker.ask[Try[String]](createMessage(value)(_)).failed.futureValue.getMessage shouldBe errorMessage
      }

      When("Short input")
      Then("return failed message")
      forAll(genShortStr) { value =>
        sabBroker.ask[Try[String]](createMessage(value)(_)).failed.futureValue.getMessage shouldBe failedMessage
      }

      And("Status Open")
      val probe2 = testKit.createTestProbe[Receptionist.Listing]()
      testKit.system.receptionist ! Receptionist.Subscribe(SABActor.SABActorServiceKey, probe2.ref)
      probe2.receiveMessage().allServiceInstances(SABActor.SABActorServiceKey).foreach { actorRef =>
        actorRef.ask[SABActor.SABStatus](reply => GetStatus(reply)).futureValue shouldBe SABStatus.Open
      }
    }

    Scenario("Future is slow in LinealBackoff") {

      Given("broker pattern 2")
      import testKit.system
      implicit val ex: ExecutionContext = testKit.system.executionContext
      implicit val timeout: Timeout     = Timeout(5.seconds)
      import akka.actor.typed.scaladsl.AskPattern._

      type T = String
      type R = String

      val sabBrokerName2: String = "broker-2"
      val messageId: String      = "id-2"
      val config: SABConfig = SABConfig(
        maxFailures = 9,
        failureDuration = 500.milliseconds,
        backoff = LinealBackoff(1.hour)
      )
      val handler: T => Future[R] = _ =>
        Future {
          Thread.sleep(1000L)
          successMessage
        }
      val sabBrokerBehavior                      = SABBroker(config, failedResponse, isFailed)
      val sabBroker: ActorRef[SABBroker.Command] = testKit.spawn(sabBrokerBehavior, sabBrokerName2)

      def createMessage(value: String): ActorRef[Try[R]] => SABBrokerMessage[T, R] =
        reply => SABBrokerMessage(SABMessage(messageId, value, handler, reply))

      When("input slow handler")
      sabBroker.ask[Try[R]](createMessage("???")(_)).futureValue shouldBe successMessage
    }

  }

}
