package eventstore

import akka.testkit.TestProbe
import ExpectedVersion._

class TransactionITest extends TestConnection {
  implicit val direction = ReadDirection.Forward
  "transaction" should {
    "start on non existing stream with correct exp ver and create stream on commit" in new TransactionScope {
      implicit val transactionId = transactionStart(NoStream)
      transactionWrite(newEventData)
      transactionCommit(Some(EventNumber.First to EventNumber.First))
    }

    "start on non existing stream with any exp ver and create stream on commit" in new TransactionScope {
      implicit val transactionId = transactionStart(Any)
      transactionWrite(newEventData)
      transactionCommit(Some(EventNumber.First to EventNumber.First))
    }

    "fail to commit on non existing stream with wrong exp ver" in new TransactionScope {
      implicit val transactionId = transactionStart(ExpectedVersion.First)
      transactionWrite(newEventData)
      failTransactionCommit must throwA[WrongExpectedVersionException]
    }

    "do nothing if commits without events to empty stream" in new TransactionScope {
      implicit val transactionId = transactionStart(NoStream)
      transactionCommit()
      readStreamEventsFailed must throwA[StreamNotFoundException]
    }

    "do nothing if commits no events to empty stream" in new TransactionScope {
      implicit val transactionId = transactionStart(NoStream)
      transactionWrite()
      transactionCommit()
      readStreamEventsFailed must throwA[StreamNotFoundException]
    }

    "validate expectations on commit" in new TransactionScope {
      implicit val transactionId = transactionStart(ExpectedVersion(1))
      transactionWrite(newEventData)
      failTransactionCommit must throwA[WrongExpectedVersionException]
    }

    "commit when writing with exp ver ANY even while someone is writing in parallel" in new TransactionScope {
      appendEventToCreateStream()

      implicit val transactionId = transactionStart(Any)

      val probe = TestProbe()

      writeEventsCompleted(List(newEventData), testKit = probe)

      transactionWrite(newEventData)

      writeEventsCompleted(List(newEventData), testKit = probe)

      transactionWrite(newEventData)
      transactionCommit(Some(EventNumber.Exact(3) to EventNumber.Exact(4)))
      streamEvents must haveSize(5)
    }

    "fail to commit if started with correct ver but committing with bad" in new TransactionScope {
      appendEventToCreateStream()
      implicit val transactionId = transactionStart(ExpectedVersion.First)
      append(newEventData).number mustEqual EventNumber(1)
      transactionWrite(newEventData)
      failTransactionCommit must throwA[WrongExpectedVersionException]
    }

    "succeed to commit if started with wrong ver but committing with correct ver" in new TransactionScope {
      appendEventToCreateStream()
      implicit val transactionId = transactionStart(ExpectedVersion(1))
      append(newEventData).number mustEqual EventNumber(1)
      transactionWrite()
      transactionCommit(None)
    }

    "fail to commit if stream has been deleted during transaction" in new TransactionScope {
      appendEventToCreateStream()
      implicit val transactionId = transactionStart(ExpectedVersion.First)
      deleteStream()
      failTransactionCommit must throwA[StreamDeletedException]
    }

    "idempotency is correct for explicit transactions with expected version any" in new TransactionScope {
      val event = newEventData
      val transactionId1 = transactionStart(Any)
      transactionWrite(event)(transactionId1)
      transactionCommit(Some(EventNumber.First to EventNumber.First))(transactionId1)

      val transactionId2 = transactionStart(Any)
      transactionWrite(event)(transactionId2)
      transactionCommit(Some(EventNumber.First to EventNumber.First), position = false)(transactionId2)
      streamEvents mustEqual List(event)
    }
  }

  private trait TransactionScope extends TestConnectionScope {

    def transactionStart(expVer: ExpectedVersion = Any): Long = {
      actor ! TransactionStart(streamId, expVer)
      expectMsgType[TransactionStartCompleted].transactionId
    }

    def transactionWrite(events: EventData*)(implicit transactionId: Long) {
      actor ! TransactionWrite(transactionId, events.toList)
      expectMsg(TransactionWriteCompleted(transactionId))
    }

    def transactionCommit(range: Option[EventNumber.Range] = None, position: Boolean = true)(implicit transactionId: Long) {
      actor ! TransactionCommit(transactionId)
      val result = expectMsgType[TransactionCommitCompleted]
      result.transactionId mustEqual transactionId
      result.numbersRange mustEqual range
      if (position) result.position must beSome else result.position must beNone
    }

    def failTransactionCommit(implicit transactionId: Long) = {
      actor ! TransactionCommit(transactionId)
      expectEsException()
    }
  }
}