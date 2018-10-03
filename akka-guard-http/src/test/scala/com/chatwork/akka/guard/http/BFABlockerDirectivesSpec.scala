package com.chatwork.akka.guard.http

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.{ FreeSpec, Matchers }

class BFABlockerDirectivesSpec extends FreeSpec with Matchers with ScalatestRouteTest {

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
