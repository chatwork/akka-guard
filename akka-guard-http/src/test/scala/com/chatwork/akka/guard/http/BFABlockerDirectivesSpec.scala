package com.chatwork.akka.guard.http

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.http.scaladsl.model.{ HttpEntity, HttpResponse, StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import com.chatwork.akka.guard.{ BFABroker, BFABrokerConfig }
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

  val bfaConfig: BFABrokerConfig[Unit, RouteResult] =
    BFABrokerConfig(
      maxFailures = 9,
      failureTimeout = 10.seconds,
      resetTimeout = 1.hour,
      failedResponse = Failure(new Exception("failed!!"))
    )

  "BFABlockerDirectivesSpec" - {
    "Success" in new WithFixture {
      forAll(genLongStr) { value =>
        Post("/login", HttpEntity.apply(value)) ~> loginRoute ~> check {
          status shouldBe StatusCodes.OK
        }
      }
      forAll(genShortStr) { value =>
        Post("/login", HttpEntity.apply(value)) ~> loginRoute ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }
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
    override implicit val timeout: Timeout = Timeout(10.seconds)

    val loginRoute: Route =
      post {
        path("login") {
          extractRequest { request =>
            bfaBlocker("id") {
              val body = request.entity.dataBytes.runWith(Sink.head).futureValue
              if (body.utf8String.length < BoundaryLength) {
                complete(HttpResponse(StatusCodes.BadRequest))
              } else {
                complete("index")
              }
            }
          }
        }
      }
  }

}
