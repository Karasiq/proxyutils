name := "proxyutils"

organization := "com.github.karasiq"

version := "2.0.11-SNAPSHOT"

isSnapshot := version.value.endsWith("SNAPSHOT")

scalaVersion := "2.12.3"

crossScalaVersions := Seq("2.11.11", "2.12.3")

resolvers += "softprops-maven" at "http://dl.bintray.com/content/softprops/maven"

// resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies ++= {
  val akkaV = "2.5.4"
  val akkaHttpV = "10.0.10"
  Seq(
    "commons-io" % "commons-io" % "2.5",
    "org.apache.httpcomponents" % "httpclient" % "4.5.3",
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-testkit" % akkaV % "test",
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaV % "test",
    "com.github.karasiq" %% "commons-akka" % "1.0.7",
    "com.github.karasiq" %% "cryptoutils" % "1.4.3",
    "org.scalatest" %% "scalatest" % "3.0.4" % "test",
    "org.bouncycastle" % "bcprov-jdk15on" % "1.58" % "provided",
    "org.bouncycastle" % "bcpkix-jdk15on" % "1.58" % "provided"
  )
}

publishMavenStyle := true

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

publishArtifact in Test := false

pomIncludeRepository := { _ ⇒ false }

licenses := Seq("Apache License, Version 2.0" → url("http://opensource.org/licenses/Apache-2.0"))

homepage := Some(url("https://github.com/Karasiq/" + name.value))

pomExtra := <scm>
  <url>git@github.com:Karasiq/{name.value}.git</url>
  <connection>scm:git:git@github.com:Karasiq/{name.value}.git</connection>
</scm>
  <developers>
    <developer>
      <id>karasiq</id>
      <name>Piston Karasiq</name>
      <url>https://github.com/Karasiq</url>
    </developer>
  </developers>