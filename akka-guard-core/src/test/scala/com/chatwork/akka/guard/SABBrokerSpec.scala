package com.chatwork.akka.guard

import akka.actor.{ ActorPath, ActorRef, ActorSelection, ActorSystem, Props }
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.PropertyChecks

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure

class SABBrokerSpec
    extends TestKit(ActorSystem("SABBrokerSpec"))
    with FeatureSpecLike
    with BeforeAndAfterAll
    with GivenWhenThen
    with PropertyChecks
    with Matchers
    with ScalaFutures {

  val BoundaryLength           = 50
  val genShortStr: Gen[String] = Gen.asciiStr.suchThat(_.length < BoundaryLength)
  val genLongStr: Gen[String]  = Gen.asciiStr.suchThat(_.length >= BoundaryLength)

  val failedMessage  = "failed!!"
  val errorMessage   = "error!!"
  val successMessage = "success!!"
  val config: SABBrokerConfig[String, String] = SABBrokerConfig[String, String](
    maxFailures = 9,
    failureTimeout = 10.seconds,
    resetTimeout = 1.hour,
    failedResponse = Failure(new Exception(failedMessage)),
    isFailed = _ => false
  )
  val handler: String => Future[String] = {
    case request if request.length < BoundaryLength  => Future.failed(new Exception(errorMessage))
    case request if request.length >= BoundaryLength => Future.successful(successMessage)
  }

  feature("SABBrokerSpec") {

    scenario("Success") {

      Given("broker pattern 1")
      implicit val timeout: Timeout  = Timeout(5.seconds)
      val sabBrokerName1: String     = "broker-1"
      val messageId: String          = "id-1"
      val sabBroker1: ActorRef       = system.actorOf(Props(new SABBroker(config)), sabBrokerName1)
      val messagePath: ActorPath     = system / sabBrokerName1 / ServiceAttackBlockerActor.name(messageId)
      val messageRef: ActorSelection = system.actorSelection(messagePath)

      When("Long input")
      Then("return success message")
      forAll(genLongStr) { value =>
        val message = SABMessage(messageId, value, handler)
        (sabBroker1 ? message).mapTo[String].futureValue shouldBe successMessage
      }

      And("Status Closed")
      (messageRef ? ServiceAttackBlockerActor.GetStatus)
        .mapTo[ServiceAttackBlockerStatus].futureValue shouldBe ServiceAttackBlockerStatus.Closed

      When("Short input")
      Then("return error message")
      forAll(genShortStr) { value =>
        val message = SABMessage(messageId, value, handler)
        (sabBroker1 ? message).mapTo[String].failed.futureValue.getMessage shouldBe errorMessage
      }

      When("Short input")
      Then("return failed message")
      forAll(genShortStr) { value =>
        val message = SABMessage(messageId, value, handler)
        (sabBroker1 ? message).mapTo[String].failed.futureValue.getMessage shouldBe failedMessage
      }

      And("Status Open")
      (messageRef ? ServiceAttackBlockerActor.GetStatus)
        .mapTo[ServiceAttackBlockerStatus].futureValue shouldBe ServiceAttackBlockerStatus.Open
    }

  }

  override protected def afterAll(): Unit = {
    shutdown()
  }
}
