import sbt._
import sbt.ModuleID

object Scala {
  val java8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0"
}

object Circe {
  val version           = "0.10.0"
  val core: ModuleID    = "io.circe" %% "circe-core" % version
  val parser: ModuleID  = "io.circe" %% "circe-parser" % version
  val generic: ModuleID = "io.circe" %% "circe-generic" % version
}

object ScalaTest {
  val v3_0_5 = "org.scalatest" %% "scalatest" % "3.0.5"
}

object Cats {
  val v1_4_0 = "org.typelevel" %% "cats-core" % "1.4.0"
}

object Enumeratum {
  val latest = "com.beachape" %% "enumeratum" % "1.5.13"
}

object ScalaCheck {
  val scalaCheck = "org.scalacheck" %% "scalacheck" % "1.14.0"
}

object Akka {
  val version           = "2.5.12"
  val actor: ModuleID   = "com.typesafe.akka" %% "akka-actor" % version
  val testKit: ModuleID = "com.typesafe.akka" %% "akka-testkit" % version
  val slf4j: ModuleID = "com.typesafe.akka" %% "akka-slf4j" % version
}

object AkkaHttp {
  val version           = "10.1.5"
  val http: ModuleID    = "com.typesafe.akka" %% "akka-http" % version
  val testKit: ModuleID = "com.typesafe.akka" %% "akka-http-testkit" % version
}
