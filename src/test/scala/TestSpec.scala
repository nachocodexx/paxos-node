import fs2.Stream
import cats.implicits._
import cats.effect._
import cats.effect.std.Queue
import fs2.concurrent.SignallingRef
import mx.cinvestav.Declarations.ProposalNumber

import concurrent.duration._
import language.postfixOps
import collection.mutable.{Queue => QQueue}
import scala.collection.immutable

class TestSpec extends munit .CatsEffectSuite {
  test("Basic Queue"){
    val q = immutable.Queue(1,5,3,7,1,2,0,0,1,2,3,1,10,2)
    val res =  q.filter(_>3).last
    println(res)
  }
  test("Queue") {
    for {
      _ <- IO.println("HOAAAAAAAA")
      q <- Queue.unbounded[IO,Option[Int]]
      qq = QQueue(1,2,3)
      _ <- IO.pure(qq.addOne(10))
      _ <- IO.println(qq)
//      _ <- Stream.fromQueueUnterminated(q)
//        .evalMap{x=>
//          IO.println(x)
//        }
//        .compile.drain.start
      _ <- IO.println("HOAAAAAAAA")
      _ <- q.offer(0.some)
      _ <- q.offer(1.some )
      _ <- q.offer(2.some)
      _ <- q.offer(2.some)
      _ <- q.offer(3.some)
      _ <- q.offer(4.some)
      _ <- q.offer(None)
      _ <- q.offer(5.some)
//      _ <- Stream.fromQueueUnterminated[IO,Option[Int]](q)
//        .evalMap{x=>
//          IO.println(x)
//        }
//        .compile.drain.start
      _ <-IO.sleep(5 seconds)
      _ <- IO.println("HOAAAAAAAA")
//      _ <- Stream.emits(List(1,2,3)).evalMap{ x=>
//        IO.println(x)
//      }.compile.drain
    } yield ()
  }
  test("Basics"){
    import mx.cinvestav.Declarations.proposalNumberOrdering
    val p0 = ProposalNumber(0,"px-0")
    val p1 = ProposalNumber(1,"px-1")
    val res = Ordering[ProposalNumber].gt(p1,p0)
    println(res)
    println(Ordering[ProposalNumber].compare(p0,p1))
//    val ProposalNumber(part0,part1) = p0
//    println(p0,part0,part1)
  }
  test("Pause a operation"){
    val program = Stream
      .eval(IO.println("I'M CAME FROM THE PAST : ) "))
      .covary[IO]
//      .evalMap(identity)
    for {
      signal <- SignallingRef[IO,Boolean](true)
      _      <- IO.println("HERE!")
      _      <- (IO.sleep(5 seconds) *> signal.set(false)).start
      _      <- program.debug(x=>"AAAAAAAAAH!!!").pauseWhen(signal).compile.drain
//      _      <-  IO.sleep(5 seconds)
//      _      <- signal.set(false)
    } yield ()
//    program.pauseWhen()
  }

}
