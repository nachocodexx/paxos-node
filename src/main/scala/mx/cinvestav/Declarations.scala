/*
 * Copyright (c)  8/5/2021 by Ignacio Castillo.
 */

package mx.cinvestav

import breeze.linalg.DenseVector
import cats.data.EitherT
import mx.cinvestav.commons.events.SavedMetadata

import scala.collection.mutable
import scala.collection.immutable
//import cats.effect.std.Queue
import cats.effect.{IO, Ref}
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.AMQPConnection
import io.circe.Json
import mx.cinvestav.commons.events.Event
import mx.cinvestav.config.DefaultConfig
import mx.cinvestav.utils.v2.PublisherV2
import org.typelevel.log4cats.Logger
import java.util.UUID

object Declarations {
  trait NodeError extends Error
  trait PaxosError extends NodeError
//  case class

  case class DecodedValueError(message:String) extends PaxosError {
    override def getMessage: String = s"Fail to decode the proposal value: $message"
  }

  case class ProposedValueNotFound(proposalNumber: ProposalNumber) extends PaxosError {
    override def getMessage: String = s"Proposed value not found for $proposalNumber"
  }
  case class ReplyToPropertyNotFound() extends PaxosError {
    override def getMessage: String = s"replyTo property not found"
  }
  case class PaxosNodeNotFound(nodeId:String) extends PaxosError {
    override def getMessage: String = s"Paxos node($nodeId) does not exist"
  }
  case class LowerProposalNumber(newProposal:ProposalNumber,oldProposal:ProposalNumber) extends PaxosError {
    override def getMessage: String = s"Proposal number $newProposal must be greater than previous proposal number $oldProposal"
  }
  case class PublisherNotFound(publisherId:String) extends PaxosError {
    override def getMessage: String = s"Publisher($getMessage) does not exist"
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


  case class NodeState(
                        paxosNode: List[PaxosNode] = Nil,
                        paxosPublishers:Map[String,PublisherV2] = Map.empty[String,PublisherV2],
                        isProposer:Boolean=true,
                        isAcceptor:Boolean=true,
                        isLearner:Boolean=true,
                        maxProposalIds:Map[String,ProposalNumber] = Map.empty[String,ProposalNumber],
                        promisesCounter:Map[ProposalNumber,Int] = Map.empty[ProposalNumber,Int],
                        proposedValues:Map[ProposalNumber,SavedMetadata] = Map.empty[ProposalNumber,SavedMetadata],
                        eventLog:immutable.Queue[Event]
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
    final val ACCEPT:String = "ACCEPT"
    final val ACCEPTED:String = "ACCEPTED"
  }
  object Events {
    //    sealed trait Event
//    case class Proposed(proposalNumber: Int, timestamp: Long, vc: DenseVector[Int], userId: UUID) extends Event
//
//    case class Promised(proposalNumber: Int, timestamp: Long, vc: DenseVector[Int], userId: UUID) extends Event
//
//    case class Committed(proposalNumber: Int, timestamp: Long, vc: DenseVector[Int], userId: UUID) extends Event
  }
  object payloads {
    case class Propose(
                        fileId:String,
                        value:String,
                        timestamp:Long
                      )
    case class Prepare(
                        proposerId:String,
                        proposalNumber: ProposalNumber,
                        fileId:String,
                        timestamp:Long
                      )
    case class Promise(
                        nodeId:String,
                        proposalNumber: ProposalNumber,
                        fileId:String,
                        relativeValue:String,
                        timestamp:Long
                      )
    case class Accept(
                       proposalNumber: ProposalNumber,
                       fileId:String,
                       value:String,
                       timestamp:Long
                     )
    case class Accepted (
                          proposalNumber: ProposalNumber,
                          fileId:String,
                          value:String,
                          timestamp:Long
                        )
  }
//  case class PrepareP()

}
