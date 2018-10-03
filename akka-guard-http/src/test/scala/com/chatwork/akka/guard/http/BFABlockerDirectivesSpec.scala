package com.chatwork.akka.guard.http

import akka.actor.{ActorRef, Props}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.chatwork.akka.guard.{BFABroker, BFABrokerConfig, BFAMessage}
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FreeSpec, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure

class BFABlockerDirectivesSpec extends FreeSpec with PropertyChecks with Matchers with ScalatestRouteTest {

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
    f = {
      case BFAMessage(_, request) if request.length < BoundaryLength  => Future.failed(new Exception(errorMessage))
      case BFAMessage(_, request) if request.length >= BoundaryLength => Future.successful(successMessage)
    }
  )
  val brokerName = "BFABroker"
  lazy val bfaActorRef: ActorRef = system.actorOf(Props(new BFABroker(config)), brokerName)

  "BFABlockerDirectivesSpec" - {

    "Success" in new WithFixture {

      val smallRoute: Route =
        get {
          pathSingleSlash {
            complete("index")
          }
        }

      Get() ~> smallRoute ~> check {
        status shouldBe StatusCodes.OK
      }

    }

  }

  trait WithFixture

}
