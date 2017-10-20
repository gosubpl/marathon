package mesosphere.marathon
package api.akkahttp.v2

import akka.event.EventStream
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, RemoteAddress, Uri }
import akka.http.scaladsl.model.headers.`X-Real-Ip`
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{ Keep, Source }
import akka.stream.{ OverflowStrategy }
import de.heikoseeberger.akkasse.EventStreamParser
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.election.{ ElectionService, LeadershipState }
import mesosphere.marathon.core.event.{ AppTerminatedEvent, SchedulerReregisteredEvent }
import mesosphere.marathon.plugin.auth.Identity
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer }
import mesosphere.marathon.state.PathId
import mesosphere.marathon.stream.{ Repeater, Sink }
import org.scalatest.Inside
import org.scalatest.exceptions.TestFailedException

import scala.concurrent.Future

class EventsControllerTest extends AkkaUnitTest with Inside {
  /* Note - We don't use ScalatestRouteTest because it forces the entire response entity to be consumed before it can be
   * accessed.
   */
  import RequestBuilding._
  val testTerminatedEvent = AppTerminatedEvent(appId = PathId("/my-app"))
  val testSchedulerReregisteredEvent = SchedulerReregisteredEvent("test-mesos-master")
  def withFixture(eventStreamMaxOutstandingMessages: Int = 5)(fn: Fixtures => Unit): Unit = {
    val f = new Fixtures(eventStreamMaxOutstandingMessages)
    try (fn(f)) finally {
      f.leaderStateEventsInput.complete()
    }
  }

  class Fixtures(val eventStreamMaxOutstandingMessages: Int = 5) {
    implicit val electionService = mock[ElectionService]
    electionService.leaderHostPort returns Some("host:1")
    electionService.localHostPort returns "host:1"
    electionService.isLeader returns true
    implicit val authorizer = mock[Authorizer]
    implicit lazy val authenticator = mock[Authenticator]
    final val maxEventQueueCapacity = 16
    val (leaderStateEventsInput, leaderStateEvents) = Source.queue[LeadershipState](maxEventQueueCapacity, OverflowStrategy.fail)
      .toMat(Repeater(maxEventQueueCapacity, OverflowStrategy.fail))(Keep.both)
      .run
    electionService.leaderStateEvents returns leaderStateEvents

    authorizer.isAuthorized(any, any, any) returns true
    authenticator.authenticate(any) returns Future.successful((Some(new Identity {})))

    val eventBus = new EventStream(system)
    val eventsController = new EventsController(
      eventStreamMaxOutstandingMessages = eventStreamMaxOutstandingMessages,
      eventBus = eventBus)

    /**
      * Makes a single request. We don't use the akka testkit `check` methods here because they all want to consume the
      * entire response before making any data available.
      *
      * Read [[https://doc.akka.io/docs/akka-http/10.0.10/java/http/routing-dsl/testkit.html#writing-asserting-against-the-httpresponse]]
      */
    def eventsRequest(request: HttpRequest): HttpResponse = {
      Source.single(request)
        .via(Route.handlerFlow(eventsController.route))
        .runWith(Sink.head)
        .futureValue
    }
  }

  "It publishes an event_stream_attached event upon connection for the connected IP" in withFixture() { f =>
    val response = f.eventsRequest(Get().addHeader(`X-Real-Ip`(RemoteAddress(Array[Byte](1, 2, 3, 4)))))

    val attachedEvent = response.entity.dataBytes
      .via(EventStreamParser(Int.MaxValue, Int.MaxValue))
      .runWith(Sink.head)
      .futureValue

    attachedEvent.`type` shouldBe Some("event_stream_attached")
    attachedEvent.data.get should include("1.2.3.4")
  }

  "Relays events that are published after it is connected" in withFixture() { f =>
    val response = f.eventsRequest(Get())

    val output = response.entity.dataBytes
      .via(EventStreamParser(Int.MaxValue, Int.MaxValue))
      .drop(1)
      .take(1)
      .runWith(Sink.head)

    f.eventBus.publish(testTerminatedEvent)

    val sse = output.futureValue
    sse.`type` shouldBe Some("app_terminated_event")
    sse.data.get should include("/my-app")
  }

  "Respects filtering conditions" in withFixture() { f =>
    val response = f.eventsRequest(Get(Uri("/?event_type=app_terminated_event")))

    val output = response.entity.dataBytes
      .via(EventStreamParser(Int.MaxValue, Int.MaxValue))
      .runWith(Sink.queue())

    f.eventBus.publish(testSchedulerReregisteredEvent)
    f.eventBus.publish(testTerminatedEvent)
    inside(output.pull().futureValue) {
      case Some(sse) =>
        // It should skip the scheduler registration event, and the original event_stream_attached event
        sse.`type` shouldBe Some("app_terminated_event")
    }
    output.cancel()
  }

  "Closes the stream when leaderhip is lost" in withFixture() { f =>
    val response = f.eventsRequest(Get(Uri("/?filter=app_terminated_event")))

    val events = response.entity.dataBytes
      .via(EventStreamParser(Int.MaxValue, Int.MaxValue))
      .runWith(Sink.seq)

    f.leaderStateEventsInput.offer(LeadershipState.Standby(None))

    events.futureValue.flatMap(_.`type`) shouldBe Seq("event_stream_attached")
  }

  "Fails for queue overload" in withFixture() { f =>
    val hideouslyLargeEventsCount = 50

    val response = f.eventsRequest(Get())

    val events = response.entity.dataBytes
      .via(EventStreamParser(Int.MaxValue, Int.MaxValue))
      .take(hideouslyLargeEventsCount)
      .runWith(Sink.seq)

    (1 to hideouslyLargeEventsCount).foreach(_ => f.eventBus.publish(testSchedulerReregisteredEvent))

    the[TestFailedException] thrownBy {
      events.futureValue
    } should have message "The future returned an exception of type: akka.stream.BufferOverflowException, with message: Buffer overflow (max capacity was: 5)!."
  }
}
