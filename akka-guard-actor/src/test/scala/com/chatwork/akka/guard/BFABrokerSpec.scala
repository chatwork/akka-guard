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

class BFABrokerSpec
    extends TestKit(ActorSystem("BFABrokerSpec"))
    with FeatureSpecLike
    with GivenWhenThen
    with PropertyChecks
    with Matchers
    with ScalaFutures {

  val BoundaryLength           = 50
  val genShortStr: Gen[String] = Gen.listOf(Gen.asciiChar).map(_.mkString).suchThat(_.length < BoundaryLength)
  val genLongStr: Gen[String]  = Gen.listOf(Gen.asciiChar).map(_.mkString).suchThat(_.length >= BoundaryLength)

  val failedMessage  = "failed!!"
  val errorMessage   = "error!!"
  val successMessage = "success!!"
  val config: BFABrokerConfig[String, String] = BFABrokerConfig[String, String](
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

  feature("BFABrokerSpec") {

    scenario("Success") {

      Given("broker pattern 1")
      implicit val timeout: Timeout  = Timeout(5.seconds)
      val bfaBrokerName1: String     = "broker-1"
      val messageId: String          = "id-1"
      val bfaBroker1: ActorRef       = system.actorOf(Props(new BFABroker(config)), bfaBrokerName1)
      val messagePath: ActorPath     = system / bfaBrokerName1 / BFABlocker.name(messageId)
      val messageRef: ActorSelection = system.actorSelection(messagePath)

      When("Long input")
      Then("return success message")
      forAll(genLongStr) { value =>
        val message = BFAMessage(messageId, value, handler)
        (bfaBroker1 ? message).mapTo[Future[String]].futureValue.futureValue shouldBe successMessage
      }

      And("Status Closed")
      (messageRef ? BFABlocker.GetStatus)
        .mapTo[BFABlockerStatus].futureValue shouldBe BFABlockerStatus.Closed

      When("Short input")
      Then("return error message")
      forAll(genShortStr) { value =>
        val message = BFAMessage(messageId, value, handler)
        (bfaBroker1 ? message).mapTo[Future[String]].futureValue.failed.futureValue.getMessage shouldBe errorMessage
      }

      When("Short input")
      Then("return failed message")
      forAll(genShortStr) { value =>
        val message = BFAMessage(messageId, value, handler)
        (bfaBroker1 ? message).mapTo[Future[String]].futureValue.failed.futureValue.getMessage shouldBe failedMessage
      }

      And("Status Open")
      (messageRef ? BFABlocker.GetStatus)
        .mapTo[BFABlockerStatus].futureValue shouldBe BFABlockerStatus.Open
    }

  }
}
