import sbt._

ThisBuild / scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value)

def crossScalacOptions(scalaVersion: String): Seq[String] = CrossVersion.partialVersion(scalaVersion) match {
  case Some((2L, scalaMajor)) if scalaMajor >= 12 =>
    Seq.empty
  case Some((2L, scalaMajor)) if scalaMajor <= 11 =>
    Seq("-Yinline-warnings")
}

val commonSettings = Seq(
  organization := "com.chatwork",
  homepage := Some(url("https://github.com/chatwork/akka-guard")),
  licenses := List("The MIT License" -> url("http://opensource.org/licenses/MIT")),
  developers := List(
    Developer(
      id = "j5ik2o",
      name = "Junichi Kato",
      email = "j5ik2o@gmail.com",
      url = url("https://blog.j5ik2o.me")
    ),
    Developer(
      id = "yoshiyoshifujii",
      name = "Yoshitaka Fujii",
      email = "yoshiyoshifujii@gmail.com",
      url = url("http://yoshiyoshifujii.hatenablog.com")
    ),
    Developer(
      id = "exoego",
      name = "TATSUNO Yasuhiro",
      email = "ytatsuno.jp@gmail.com",
      url = url("https://www.exoego.net")
    )
  ),
  scalaVersion := ScalaVersions.scala213Version,
  scalacOptions ++= (
    Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      "-encoding",
      "UTF-8",
      "-language:_",
      "-Ydelambdafy:method",
      "-target:jvm-1.8",
      "-Yrangepos",
      "-Ywarn-unused"
    ) ++ crossScalacOptions(scalaVersion.value)
  ),
  resolvers ++= Seq(
    Resolver.sonatypeRepo("snapshots"),
    Resolver.sonatypeRepo("releases")
  ),
  libraryDependencies ++= Seq(
    ScalaTest.scalatest      % Test,
    ScalaTestPlus.scalacheck % Test,
    ScalaCheck.scalaCheck    % Test,
    Enumeratum.latest,
    ScalaLangModules.java8Compat
  ),
  updateOptions := updateOptions.value.withCachedResolution(true),
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision,
  Test / parallelExecution := false,
  Test / run / javaOptions ++= Seq("-Xms4g", "-Xmx4g", "-Xss10M", "-XX:+CMSClassUnloadingEnabled"),
  Test / publishArtifact := false
)

lazy val `akka-guard-core` = (project in file("akka-guard-core"))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-guard-core",
    crossScalaVersions := Seq(
      ScalaVersions.scala211Version,
      ScalaVersions.scala212Version,
      ScalaVersions.scala213Version
    ),
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2L, scalaMajor)) if scalaMajor == 13 =>
          Seq(
            Akka.Version2_6.testKit % Test,
            Cats.Version2_1.core,
            Circe.Version0_13.core,
            Circe.Version0_13.generic,
            Circe.Version0_13.parser,
            Akka.Version2_6.actor,
            Akka.Version2_6.slf4j,
            Logback.classic
          )
        case Some((2L, scalaMajor)) if scalaMajor == 12 =>
          Seq(
            Akka.Version2_6.testKit % Test,
            ScalaLangModules.collectionCompat,
            Cats.Version2_1.core,
            Circe.Version0_13.core,
            Circe.Version0_13.generic,
            Circe.Version0_13.parser,
            Akka.Version2_6.actor,
            Akka.Version2_6.slf4j,
            Logback.classic
          )
        case Some((2L, scalaMajor)) if scalaMajor == 11 =>
          Seq(
            Akka.Version2_5.testKit % Test,
            ScalaLangModules.collectionCompat,
            Cats.Version2_0.core,
            Circe.Version0_11.core,
            Circe.Version0_11.generic,
            Circe.Version0_11.parser,
            Akka.Version2_5.actor,
            Akka.Version2_5.slf4j
          )
      }
    }
  )

lazy val `akka-guard-http` = (project in file("akka-guard-http"))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-guard-http",
    crossScalaVersions := Seq(
      ScalaVersions.scala211Version,
      ScalaVersions.scala212Version,
      ScalaVersions.scala213Version
    ),
    libraryDependencies ++= Seq(
      AkkaHttp.testKit % Test,
      AkkaHttp.http
    ),
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2L, scalaMajor)) if scalaMajor == 13 =>
          Seq(
            Akka.Version2_6.testKit       % Test,
            Akka.Version2_6.streamTestKit % Test,
            Akka.Version2_6.stream
          )
        case Some((2L, scalaMajor)) if scalaMajor == 12 =>
          Seq(
            Akka.Version2_6.testKit       % Test,
            Akka.Version2_6.streamTestKit % Test,
            Akka.Version2_6.stream
          )
        case Some((2L, scalaMajor)) if scalaMajor == 11 =>
          Seq(
            Akka.Version2_5.testKit       % Test,
            Akka.Version2_5.streamTestKit % Test,
            Akka.Version2_5.stream
          )
      }
    }
  )
  .dependsOn(`akka-guard-core`)

lazy val `akka-guard-core-typed` = (project in file("akka-guard-core-typed"))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-guard-core-typed",
    libraryDependencies ++= Seq(
      Akka.Version2_6.testKitTyped % Test,
      Cats.Version2_1.core,
      Circe.Version0_13.core,
      Circe.Version0_13.generic,
      Circe.Version0_13.parser,
      Akka.Version2_6.actorTyped,
      Akka.Version2_6.slf4j,
      Logback.classic
    )
  )

lazy val `akka-guard-http-typed` = (project in file("akka-guard-http-typed"))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-guard-http-typed",
    libraryDependencies ++= Seq(
      AkkaHttp.testKit              % Test,
      Akka.Version2_6.streamTestKit % Test,
      Akka.Version2_6.testKitTyped  % Test,
      AkkaHttp.http,
      Akka.Version2_6.streamTyped
    )
  )
  .dependsOn(`akka-guard-core-typed`)

lazy val root = (project in file("."))
  .settings(commonSettings: _*)
  .settings(
    name := "akka-guard-root",
    publish / skip := true
  )
  .aggregate(`akka-guard-core`, `akka-guard-http`, `akka-guard-core-typed`, `akka-guard-http-typed`)

// --- Custom commands
addCommandAlias("lint", ";scalafmtCheck;test:scalafmtCheck;scalafmtSbtCheck;scalafixAll --check")
addCommandAlias("fmt", ";scalafmtAll;scalafmtSbt")
