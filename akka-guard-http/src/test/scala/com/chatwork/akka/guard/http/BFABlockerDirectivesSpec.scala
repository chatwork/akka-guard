package com.chatwork.akka.guard.http

import akka.actor.{ ActorPath, ActorSelection, ActorSystem }
import akka.http.scaladsl.model.{ HttpEntity, StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.pattern.ask
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import com.chatwork.akka.guard.{ BFABlocker, BFABlockerStatus, BFABrokerConfig }
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.PropertyChecks
import org.scalatest.{ FreeSpec, Matchers }

import scala.concurrent.duration._
import scala.util.Failure

class BFABlockerDirectivesSpec
    extends FreeSpec
    with PropertyChecks
    with Matchers
    with ScalatestRouteTest
    with ScalaFutures {

  val BoundaryLength           = 50
  val genShortStr: Gen[String] = Gen.listOf(Gen.asciiChar).map(_.mkString).suchThat(_.length < BoundaryLength)
  val genLongStr: Gen[String]  = Gen.listOf(Gen.asciiChar).map(_.mkString).suchThat(_.length >= BoundaryLength)
  val clientId                 = "id-1"
  val uri                      = s"/login/$clientId"

  "BFABlockerDirectivesSpec" - {
    "Success" in new WithFixture {
      forAll(genLongStr) { value =>
        Post(uri, HttpEntity(value)) ~> loginRoute ~> check {
          status shouldBe StatusCodes.OK
        }
      }
      forAll(genShortStr) { value =>
        Post(uri, HttpEntity(value)) ~> loginRoute ~> check {
          status shouldBe StatusCodes.InternalServerError
        }
      }

      implicit val timeout: Timeout  = Timeout(4.second)
      val messagePath: ActorPath     = system / bfaActorName / BFABlocker.name(clientId)
      val messageRef: ActorSelection = system.actorSelection(messagePath)

      messageRef
        .?(BFABlocker.GetStatus)
        .mapTo[BFABlockerStatus]
        .futureValue shouldBe BFABlockerStatus.Closed

      // TODO I want to make it unnecessary to tick here
      messageRef ! BFABlocker.Tick

      forAll(genShortStr) { value =>
        Post(uri, HttpEntity(value)) ~> loginRoute ~> check {
          status shouldBe StatusCodes.InternalServerError
        }
      }

      messageRef
        .?(BFABlocker.GetStatus)
        .mapTo[BFABlockerStatus]
        .futureValue shouldBe BFABlockerStatus.Open
    }
  }

  trait WithFixture extends BFABlockerDirectives {
    override protected val bfaActorSystem: ActorSystem = system
    override protected val bfaConfig: BFABrokerConfig[Unit, RouteResult] =
      BFABrokerConfig(
        maxFailures = 9,
        failureTimeout = 10.seconds,
        resetTimeout = 1.hour,
        failedResponse = Failure(new Exception("failed!!"))
      )

    val loginRoute: Route =
      post {
        path("login" / Segment) { id =>
          extractRequest { request =>
            bfaBlocker(id) {
              val body = request.entity.dataBytes.runWith(Sink.head).futureValue
              if (body.utf8String.length < BoundaryLength) {
                throw new RuntimeException("")
              } else {
                complete("index")
              }
            }
          }
        }
      }
  }

}
