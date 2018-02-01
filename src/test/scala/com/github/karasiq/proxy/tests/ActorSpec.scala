package com.github.karasiq.proxy.tests

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, Matchers, Suite}

abstract class ActorSpec extends TestKit(ActorSystem("test")) with Suite with Matchers with BeforeAndAfterAll {
  implicit val materializer = ActorMaterializer()

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
}
