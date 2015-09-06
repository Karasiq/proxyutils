name := "proxyutils"

organization := "com.karasiq"

version := "1.0"

scalaVersion := "2.11.7"

resolvers += "softprops-maven" at "http://dl.bintray.com/content/softprops/maven"

libraryDependencies ++= Seq(
  "commons-io" % "commons-io" % "2.4",
  "org.apache.httpcomponents" % "httpclient" % "4.3.3",
  "com.typesafe.akka" %% "akka-actor" % "2.3.11",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.11" % "test",
  "com.karasiq" %% "akka-commons" % "1.0",
  "org.scalatest" %% "scalatest" % "2.2.4" % "test"
)

scalacOptions ++= Seq("-optimize", "-deprecation", "-feature")