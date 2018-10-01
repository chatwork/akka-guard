package com.chatwork.akka.guard

import java.util.concurrent.Executors

import akka.actor._
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.Failure

class BFABrokerSpec extends TestKit(ActorSystem("BFABrokerSpec")) with FreeSpecLike with Matchers with ScalaFutures {
  implicit val executionContext: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(200, Millis))

  "BFABroker" - {

    "Success" - {

      "The initial value is a closed state and it is necessary to process the request as usual" in {
        implicit val timeout: Timeout = Timeout(3.seconds)

        val config = BFABrokerConfig[String, String](
          maxFailures = 5L,
          failureTimeout = 10.seconds,
          resetTimeout = 30.seconds,
          failedResponse = Failure(new Exception("hoge")),
          f = message => Future.successful(message.request + "hoge")
        )
        val brokerName = "broker"
        val id = "lha"

        system.actorOf(Props(new BFABroker(config)), brokerName)

        val res = system.actorSelection(system / brokerName) ? BFAMessage(id = id, request = "request")
        res.mapTo[Future[String]].futureValue.futureValue shouldBe "requesthoge"

        val res2 = system.actorSelection(system / brokerName / BFABlocker.name(id)) ? BFABlocker.GetStatus
        res2.mapTo[BFABlockerStatus].futureValue shouldBe BFABlockerStatus.Closed
      }

    }

  }

}
