/*
 * Copyright (c)  8/5/2021 by Ignacio Castillo.
 */

package mx.cinvestav

import breeze.linalg.DenseVector
import cats.data.EitherT
import cats.effect.std.Queue
import cats.effect.{IO, Ref}
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.AMQPConnection
import io.circe.Json
import mx.cinvestav.commons.events.Event
import mx.cinvestav.config.DefaultConfig
import mx.cinvestav.utils.v2.PublisherV2
import org.typelevel.log4cats.Logger

import java.util
import java.util.UUID

object Declarations {
  trait NodeError extends Error
  trait PaxosError extends NodeError
//  case class

  case class PaxosNodeDoesNotExist(nodeId:String) extends PaxosError {
    override def getMessage: String = s"Paxos node($nodeId) does not exist"
  }
  case class LowerProposalNumber(newProposal:ProposalNumber,oldProposal:ProposalNumber) extends PaxosError {
    override def getMessage: String = s"Proposal number $newProposal must be greater than previous proposal number $oldProposal"
  }
  type MaybeF[A] = EitherT[IO,NodeError,A]
  def liftFF[A] = EitherT.liftF[IO,NodeError,A](_)
//  case class Event
  case class ProposalNumber(number:Long,nodeId:String){
      def  unapply(proposalNumber:String) :Option[(Long,Int)] = {
        val xs = proposalNumber.split('.')
        if(xs.length == 2){
          val part0 = xs(0).toLongOption
          val part1 = xs(1).toIntOption
          part0.zip(part1)
        }
        else None
      }
      def value:String = s"$number.$nodeId"
  }
  implicit val proposalNumberOrdering:Ordering[ProposalNumber] = Ordering.by(_.number)
//    (x: ProposalNumber, y: ProposalNumber) => x.number.compare(y.number)

//  implicit val proposalNumberOrder:Ordered[ProposalNumber] = new Ordered[ProposalNumber]{
//    override def compare(that: ProposalNumber): Int = th
//  }

  case class RabbitContext(client:RabbitClient[IO],connection:AMQPConnection)
  case class PaxosNode(nodeId:String,role:String)
  case class NodeContext(
                        config:DefaultConfig,
                        logger: Logger[IO],
                        rabbitContext: RabbitContext,
                        state:Ref[IO,NodeState]
                        )

//  sealed trait Operation
//  case object Add extends Operation {
//    override def toString: String = "ADD"
//  }
//  case object Remove extends Operation{
//    override def toString: String = "REMOVE"
//  }

//  case class Record(operation:Operation,data:Json)

  case class NodeState(
                        paxosNode: List[PaxosNode] = Nil,
                        paxosPublishers:Map[String,PublisherV2] = Map.empty[String,PublisherV2],
                        isProposer:Boolean=true,
                        isAcceptor:Boolean=true,
                        isLearner:Boolean=true,
                        maxProposalIds:Map[String,ProposalNumber] = Map.empty[String,ProposalNumber],
                        log: List[Json] = Nil,
                        proposes:Map[ProposalNumber,Int] = Map.empty[ProposalNumber,Int],
                        eventLog:Queue[IO,Event]
                      ){

//    SET FILE_0 sn0,sn1,sn2 0
//    SET FILE_0 sn0,sn1 1
//    SET FILE_0 sn0,sn1 2
//    def vectorClock:DenseVector[Int] =
  }
  //
  object commandsIds{
    final val PROPOSE:String ="PROPOSE"
    final val PREPARE:String = "PREPARE"
    final val PROMISE:String = "PROMISE"
    final val COMMIT:String = "COMMIT"
  }
  object Events {
    //    sealed trait Event
    case class Proposed(proposalNumber: Int, timestamp: Long, vc: DenseVector[Int], userId: UUID) extends Event

    case class Promised(proposalNumber: Int, timestamp: Long, vc: DenseVector[Int], userId: UUID) extends Event

    case class Committed(proposalNumber: Int, timestamp: Long, vc: DenseVector[Int], userId: UUID) extends Event
  }
  object payloads {
    case class Propose(fileId:String, userId:String, timestamp:Long)
    case class Prepare(
                        proposerId:String,
                        proposalNumber: ProposalNumber,
                        fileId:String,
                        userId:String,
                        timestamp:Long
                      )
    case class Promise(nodeId:String,proposalNumber: ProposalNumber,fileId:String,timestamp:Long)
    case class Commit(proposalNumber: ProposalNumber,timestamp:Long)
  }
//  case class PrepareP()

}
