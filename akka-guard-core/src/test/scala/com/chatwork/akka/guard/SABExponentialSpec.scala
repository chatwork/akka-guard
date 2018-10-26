package com.chatwork.akka.guard

import akka.actor.{ ActorPath, ActorRef, ActorSelection, ActorSystem, Cancellable, Props }
import akka.pattern.ask
import akka.testkit.{ TestKit, TestProbe }
import akka.util.Timeout
import com.chatwork.akka.guard.SABActor.BecameClosed
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.PropertyChecks
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Try }

class SABExponentialSpec
    extends TestKit(ActorSystem("SABExponentialSpec"))
    with FreeSpecLike
    with BeforeAndAfterAll
    with PropertyChecks
    with Matchers
    with ScalaFutures {
  val BoundaryLength              = 50
  val failedMessage               = "failed!!"
  val errorMessage                = "error!!"
  val successMessage              = "success!!"
  val failedResponse: Try[String] = Failure(new Exception(failedMessage))
  val isFailed: String => Boolean = _ => false

  "SABExponential" - {
    "auto reset" in {
      implicit val timeout: Timeout = Timeout(5 seconds)
      val sabBrokerName1: String    = "broker-1"
      val messageId: String         = "id-1"
      val config: SABConfig = SABConfig(
        maxFailures = 9,
        failureDuration = 10.seconds,
        backoff = ExponentialBackoff(minBackoff = 2 seconds, maxBackoff = 10 seconds, randomFactor = 0.2)
      )
      val handler: String => Future[String] = {
        case request if request.length < BoundaryLength  => Future.failed(new Exception(errorMessage))
        case request if request.length >= BoundaryLength => Future.successful(successMessage)
      }
      val testProbe: TestProbe = TestProbe()
      val sabBroker: ActorRef = system.actorOf(
        Props(
          new SABBroker(config, failedResponse, isFailed) {
            override protected def props(id: ID): Props = Props(
              new SABSupervisor[String, String](
                id,
                config,
                failedResponse = failedResponse,
                isFailed = isFailed,
                eventHandler = None
              ) {
                override protected def props(id: ID): Props = Props(
                  config.backoff match {
                    case b: ExponentialBackoff =>
                      new ExponentialBackoffActor[String, String](
                        id,
                        maxFailures = config.maxFailures,
                        backoff = b,
                        failureTimeout = config.failureDuration,
                        failedResponse = failedResponse,
                        isFailed = isFailed,
                        eventHandler = None
                      ) {
                        override protected def createScheduler(delay: FiniteDuration, attempt: Long): Cancellable = {
                          testProbe.ref ! BecameClosed(attempt, 0, setTimer = true)
                          Cancellable.alreadyCancelled
                        }
                      }
                    case _: LinealBackoff => fail()
                  }
                )
              }
            )
          }
        ),
        sabBrokerName1
      )
      val messagePath: ActorPath     = system / sabBrokerName1 / SABSupervisor.name(messageId) / SABActor.name(messageId)
      val messageRef: ActorSelection = system.actorSelection(messagePath)

      val message1 = SABMessage(messageId, "A" * 50, handler)
      (sabBroker ? message1).mapTo[String].futureValue shouldBe successMessage

      (messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue shouldBe SABStatus.Closed

      (messageRef ? SABActor.GetAttemptRequest(messageId))
        .mapTo[SABActor.GetAttemptResponse].futureValue.attempt shouldBe 0

      val message2 = SABMessage(messageId, "A" * 49, handler)
      for { _ <- 1 to 10 } (sabBroker ? message2).mapTo[String].failed.futureValue

      (messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue shouldBe SABStatus.Open

      testProbe.expectMsg(BecameClosed(1, 0, setTimer = true))
      messageRef ! BecameClosed(1, 0, setTimer = true)

      (messageRef ? SABActor.GetAttemptRequest(messageId))
        .mapTo[SABActor.GetAttemptResponse].futureValue.attempt shouldBe 1

      (messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue == SABStatus.Closed

      for { _ <- 1 to 10 } (sabBroker ? message2).mapTo[String].failed.futureValue

      (messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue shouldBe SABStatus.Open

      testProbe.expectMsg(BecameClosed(2, 0, setTimer = true))
      messageRef ! BecameClosed(2, 0, setTimer = true)

      (messageRef ? SABActor.GetAttemptRequest(messageId))
        .mapTo[SABActor.GetAttemptResponse].futureValue.attempt shouldBe 2

      (messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue == SABStatus.Closed

      for { _ <- 1 to 10 } (sabBroker ? message2).mapTo[String].failed.futureValue

      (messageRef ? SABActor.GetStatus)
        .mapTo[SABStatus].futureValue shouldBe SABStatus.Open

      (messageRef ? SABActor.GetAttemptRequest(messageId))
        .mapTo[SABActor.GetAttemptResponse].futureValue.attempt shouldBe 3

      testProbe.expectMsg(BecameClosed(0, 0, setTimer = true))
      messageRef ! BecameClosed(0, 0, setTimer = true)

      (messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue == SABStatus.Closed

      for { _ <- 1 to 10 } (sabBroker ? message2).mapTo[String].failed.futureValue

      (messageRef ? SABActor.GetStatus)
        .mapTo[SABStatus].futureValue shouldBe SABStatus.Open

      (messageRef ? SABActor.GetAttemptRequest(messageId))
        .mapTo[SABActor.GetAttemptResponse].futureValue.attempt shouldBe 1

      testProbe.expectMsg(BecameClosed(1, 0, setTimer = true))
      messageRef ! BecameClosed(1, 0, setTimer = true)
      (messageRef ? SABActor.GetStatus).mapTo[SABStatus].futureValue == SABStatus.Closed

    }
  }

  override implicit def patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(5, Millis))

  override protected def afterAll(): Unit = {
    shutdown()
  }
}
