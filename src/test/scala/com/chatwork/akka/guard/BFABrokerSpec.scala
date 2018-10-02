package com.chatwork.akka.guard

import java.util.concurrent.Executors

import akka.actor._
import akka.pattern.ask
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import org.scalacheck._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.control.NonFatal

class BFABrokerSpec
    extends TestKit(ActorSystem("BFABrokerSpec"))
    with FreeSpecLike
    with Matchers
    with ScalaFutures
    with GeneratorDrivenPropertyChecks {

  implicit val executionContext: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(200, Millis))

  implicit val timeout: Timeout = Timeout(50.seconds)

  "BFABroker" - {

    "Success" - {

      "Close state" in {
        val success       = (message: BFAMessage[String]) => s"${message.request}-success"
        val failedMessage = "bar"

        val func: BFAMessage[String] => Future[String] = msg => Future.successful(success(msg))

        implicit val arbConfig: Arbitrary[(String, BFABrokerConfig[String, String])] = Arbitrary(
          for {
            num            <- Arbitrary.arbInt.arbitrary.map(_.toString)
            maxFailures    <- Gen.chooseNum(0, 100)
            failureTimeout <- Gen.chooseNum(0, 100).map(l => FiniteDuration.apply(l, "s"))
            resetTimeout   <- Gen.chooseNum(0, 100).map(l => FiniteDuration.apply(l, "s"))
          } yield
            num -> BFABrokerConfig[String, String](
              maxFailures = maxFailures,
              failureTimeout = failureTimeout,
              resetTimeout = resetTimeout,
              failedResponse = Failure(new Exception(failedMessage)),
              f = func
            )
        )

        implicit val arbMessage: Arbitrary[String => BFAMessage[String]] = Arbitrary(
          for {
            request <- Gen.alphaStr
          } yield (id: String) => BFAMessage(id, request)
        )

        forAll(minSuccessful(5)) { tuple: (String, BFABrokerConfig[String, String]) =>
          {
            val BrokerName = s"broker-${tuple._1}"
            val brokerRef = system.actorOf(Props(new BFABroker(tuple._2)), BrokerName)

            forAll { (generator: String => BFAMessage[String]) =>
              {
                val message = generator(tuple._1)
                (system.actorSelection(system / BrokerName) ? message)
                  .mapTo[Future[String]].futureValue.futureValue shouldBe func(message).futureValue

                (system.actorSelection(system / BrokerName / BFABlocker.name(message.id)) ? BFABlocker.GetStatus)
                  .mapTo[BFABlockerStatus].futureValue shouldBe BFABlockerStatus.Closed
              }
            }
            system.stop(brokerRef)
          }
        }
      }

      "Transition to the Open state" in {
        val error                 = (message: BFAMessage[String]) => s"$message-foo"
        val failedResponseMessage = "bar"

        val config = BFABrokerConfig[String, String](
          maxFailures = 0L,
          failureTimeout = 1.seconds,
          resetTimeout = 5.seconds,
          failedResponse = Failure(new Exception(failedResponseMessage)),
          f = message => Future.failed(new Exception(error(message)))
        )

        implicit val arbMessage: Arbitrary[BFAMessage[String]] = Arbitrary(
          for {
            id      <- Gen.const("id")
            request <- Gen.alphaStr
          } yield BFAMessage(id, request)
        )

        val brokerName = "broker-2"
        val brokerRef = system.actorOf(Props(new BFABroker(config)), brokerName)

        val message = BFAMessage("id", "hoge")
        (brokerRef ? message)
          .mapTo[Future[String]].futureValue.failed.futureValue.getMessage shouldBe error(message)

        Thread.sleep(1000L)

        forAll { message: BFAMessage[String] =>
          {
            (brokerRef ? message)
              .mapTo[Future[String]].futureValue.failed.futureValue.getMessage shouldBe failedResponseMessage

            (system.actorSelection(system / brokerName / BFABlocker.name(message.id)) ? BFABlocker.GetStatus)
              .mapTo[BFABlockerStatus].futureValue shouldBe BFABlockerStatus.Open
          }
        }

        system.stop(brokerRef)
      }

    }

  }

}
