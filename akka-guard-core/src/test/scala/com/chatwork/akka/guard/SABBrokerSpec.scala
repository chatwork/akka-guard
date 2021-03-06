package com.chatwork.akka.guard

import akka.actor.{ ActorPath, ActorRef, ActorSelection, ActorSystem, Props }
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import org.scalacheck.Gen
import org.scalatest.{ BeforeAndAfterAll, GivenWhenThen }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.featurespec.AnyFeatureSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Try }

class SABBrokerSpec
    extends TestKit(ActorSystem("SABBrokerSpec"))
    with AnyFeatureSpecLike
    with BeforeAndAfterAll
    with GivenWhenThen
    with ScalaCheckPropertyChecks
    with Matchers
    with ScalaFutures {

  val BoundaryLength           = 50
  val genShortStr: Gen[String] = Gen.asciiStr.suchThat(_.length < BoundaryLength)
  val genLongStr: Gen[String]  = Gen.asciiStr.suchThat(_.length >= BoundaryLength)

  val failedMessage               = "failed!!"
  val errorMessage                = "error!!"
  val successMessage              = "success!!"
  val failedResponse: Try[String] = Failure(new Exception(failedMessage))
  val isFailed: String => Boolean = _ => false

  val testTimeFactor: Int = sys.env.getOrElse("TEST_TIME_FACTOR", "1").toInt

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(
      timeout = scaled(Span(30 * testTimeFactor, Seconds)),
      interval = scaled(Span(5 * testTimeFactor, Millis))
    )

  Feature("SABBrokerSpec untyped") {

    Scenario("Success in LinealBackoff") {

      Given("broker pattern 1")
      implicit val timeout: Timeout = Timeout((5 * testTimeFactor).seconds)
      val sabBrokerName1: String    = "broker-1"
      val messageId: String         = "id-1"
      val config: SABConfig = SABConfig(
        maxFailures = 9,
        failureDuration = (10 * testTimeFactor).seconds,
        backoff = LinealBackoff((1 * testTimeFactor).hour)
      )
      val handler: String => Future[String] = {
        case request if request.length < BoundaryLength  => Future.failed(new Exception(errorMessage))
        case request if request.length >= BoundaryLength => Future.successful(successMessage)
      }
      val sabBroker: ActorRef        = system.actorOf(Props(new SABBroker(config, failedResponse, isFailed)), sabBrokerName1)
      val messagePath: ActorPath     = system / sabBrokerName1 / SABSupervisor.name(messageId) / SABActor.name(messageId)
      val messageRef: ActorSelection = system.actorSelection(messagePath)

      When("Long input")
      Then("return success message")
      forAll(genLongStr) { value =>
        val message = SABMessage(messageId, value, handler)
        (sabBroker ? message).mapTo[String].futureValue shouldBe successMessage
      }

      And("Status Closed")
      (messageRef ? SABActor.GetStatus)
        .mapTo[SABStatus].futureValue shouldBe SABStatus.Closed

      When("Short input")
      Then("return error message")
      forAll(genShortStr) { value =>
        val message = SABMessage(messageId, value, handler)
        (sabBroker ? message).mapTo[String].failed.futureValue.getMessage shouldBe errorMessage
      }

      When("Short input")
      Then("return failed message")
      forAll(genShortStr) { value =>
        val message = SABMessage(messageId, value, handler)
        (sabBroker ? message).mapTo[String].failed.futureValue.getMessage shouldBe failedMessage
      }

      And("Status Open")
      (messageRef ? SABActor.GetStatus)
        .mapTo[SABStatus].futureValue shouldBe SABStatus.Open
    }

    Scenario("Future is slow in LinealBackoff") {

      Given("broker pattern 2")
      import system.dispatcher
      implicit val timeout: Timeout = Timeout((5 * testTimeFactor).seconds)
      val sabBrokerName2: String    = "broker-2"
      val messageId: String         = "id-2"
      val config: SABConfig = SABConfig(
        maxFailures = 9,
        failureDuration = (500 * testTimeFactor).milliseconds,
        backoff = LinealBackoff((1 * testTimeFactor).hour)
      )
      val handler: String => Future[String] = _ =>
        Future {
          Thread.sleep(1000L * testTimeFactor)
          successMessage
        }
      val sabBroker: ActorRef = system.actorOf(Props(new SABBroker(config, failedResponse, isFailed)), sabBrokerName2)

      When("input slow handler")
      val message = SABMessage(messageId, "???", handler)
      (sabBroker ? message).mapTo[String].futureValue shouldBe successMessage
    }

  }

  override protected def afterAll(): Unit = {
    shutdown()
  }
}
