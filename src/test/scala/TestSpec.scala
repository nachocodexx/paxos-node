import fs2.Stream
import cats.implicits._
import cats.effect._
import fs2.concurrent.SignallingRef
import mx.cinvestav.Declarations.ProposalNumber
import concurrent.duration._
import language.postfixOps

class TestSpec extends munit .CatsEffectSuite {
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
