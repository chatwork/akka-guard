package com.chatwork.akka.guard

import akka.actor.{ ActorPath, ActorRef, ActorSelection, ActorSystem, Props }
import akka.pattern.ask
import akka.testkit.{ ImplicitSender, TestKit }
import akka.util.{ Timeout => AkkaTimeout }
import org.scalacheck.Gen
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

class SABReceiveTimeoutSpec
    extends TestKit(ActorSystem("SABReceiveTimeoutSpec"))
    with AnyFreeSpecLike
    with BeforeAndAfterAll
    with ScalaCheckPropertyChecks
    with Matchers
    with ScalaFutures
    with Eventually
    with ImplicitSender {
  val BoundaryLength: Int      = 50
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
      timeout = scaled(Span(3 * testTimeFactor, Seconds)),
      interval = scaled(Span(1 * testTimeFactor, Millis))
    )

  "SABReceiveTimeout untyped" - {
    "receive timeout" in {
      implicit val timeout: AkkaTimeout = AkkaTimeout((5 * testTimeFactor).seconds)
      val sabBrokerName1: String        = "broker-1"
      val messageId: String             = "id-1"
      val config: SABConfig = SABConfig(
        maxFailures = 9,
        failureDuration = (10 * testTimeFactor).seconds,
        backoff = ExponentialBackoff(
          minBackoff = (1 * testTimeFactor).seconds,
          maxBackoff = (5 * testTimeFactor).seconds,
          randomFactor = 0.2
        ),
        guardResetTimeout = Some((3 * testTimeFactor).seconds)
      )
      val handler: String => Future[String] = {
        case request if request.length < BoundaryLength  => Future.failed(new Exception(errorMessage))
        case request if request.length >= BoundaryLength => Future.successful(successMessage)
      }
      val sabBroker: ActorRef        = system.actorOf(Props(new SABBroker(config, failedResponse, isFailed)), sabBrokerName1)
      val messagePath: ActorPath     = system / sabBrokerName1 / SABSupervisor.name(messageId) / SABActor.name(messageId)
      def messageRef: ActorSelection = system.actorSelection(messagePath)

      val message1 = SABMessage(messageId, "A" * BoundaryLength, handler)
      (sabBroker ? message1).mapTo[String].futureValue shouldBe successMessage

      Thread.sleep(1000 * 5 * testTimeFactor)

      (sabBroker ? message1).mapTo[String].futureValue shouldBe successMessage

      eventually(Timeout((15 * testTimeFactor).seconds)) {
        messageRef ! SABActor.GetStatus
        val result = expectMsgType[SABStatus]((1 * testTimeFactor).seconds)
        println(s"1) GetStatus: result = $result")
        result shouldBe SABStatus.Closed
      }

      val message2 = SABMessage(messageId, "A" * (BoundaryLength - 1), handler)
      for { _ <- 1 to 10 } (sabBroker ? message2).mapTo[String].failed.futureValue

      eventually(Timeout((15 * testTimeFactor).seconds)) {
        messageRef ! SABActor.GetStatus
        val result = expectMsgType[SABStatus]((1 * testTimeFactor).seconds)
        println(s"2) GetStatus: result = $result")
        result shouldBe SABStatus.Open
      }

      (messageRef ? SABActor.GetAttemptRequest(messageId))
        .mapTo[SABActor.GetAttemptResponse].futureValue.attempt shouldBe 1

    }
  }

  override protected def afterAll(): Unit = {
    shutdown()
  }
}
