package com.chatwork.akka.guard

import akka.actor.{ ActorPath, ActorRef, ActorSelection, ActorSystem, Props }
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.PropertyChecks
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Try }

class SABReceiveTimeoutSpec
    extends TestKit(ActorSystem("SABReceiveTimeoutSpec"))
    with FreeSpecLike
    with BeforeAndAfterAll
    with PropertyChecks
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

  "SABReceiveTimeout" - {
    "receive timeout" in {
      implicit val timeout: Timeout = Timeout(5 seconds)
      val sabBrokerName1: String    = "broker-1"
      val messageId: String         = "id-1"
      val config: SABBrokerConfig = SABBrokerConfig(
        maxFailures = 9,
        failureTimeout = 10.seconds,
        backoff = ExponentialBackoff(minBackoff = 1 seconds, maxBackoff = 5 seconds, randomFactor = 0.2),
        receiveTimeout = Some(3 seconds)
      )
      val handler: String => Future[String] = {
        case request if request.length < BoundaryLength  => Future.failed(new Exception(errorMessage))
        case request if request.length >= BoundaryLength => Future.successful(successMessage)
      }
      val sabBroker: ActorRef        = system.actorOf(Props(new SABBroker(config, failedResponse, isFailed)), sabBrokerName1)
      val messagePath: ActorPath     = system / sabBrokerName1 / SABSupervisor.name(messageId) / SABActor.name(messageId)
      val messageRef: ActorSelection = system.actorSelection(messagePath)

      val message1 = SABMessage(messageId, "A" * 50, handler)
      (sabBroker ? message1).mapTo[String].futureValue shouldBe successMessage

      Thread.sleep(1000 * 5)

      (sabBroker ? message1).mapTo[String].futureValue shouldBe successMessage

      (messageRef ? SABActor.GetStatus)
        .mapTo[SABStatus].futureValue shouldBe SABStatus.Closed

      val message2 = SABMessage(messageId, "A" * 49, handler)
      for { _ <- 1 to 10 } (sabBroker ? message2).mapTo[String].failed.futureValue

      (messageRef ? SABActor.GetStatus)
        .mapTo[SABStatus].futureValue shouldBe SABStatus.Open

      (messageRef ? SABActor.GetAttemptRequest(messageId))
        .mapTo[SABActor.GetAttemptResponse].futureValue.attempt shouldBe 1

    }
  }

  override implicit def patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(5, Millis))

  override protected def afterAll(): Unit = {
    shutdown()
  }
}
