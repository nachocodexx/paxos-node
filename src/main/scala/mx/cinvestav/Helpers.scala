/*
 * Copyright (c)  8/6/2021 by Ignacio Castillo.
 */

package mx.cinvestav

import cats.implicits._
import cats.effect._
import cats.data.EitherT
import dev.profunktor.fs2rabbit.model.AmqpFieldValue.StringVal
import dev.profunktor.fs2rabbit.model.{AmqpMessage, AmqpProperties}
import mx.cinvestav.Declarations.{MaybeF, NodeContext, NodeError, NodeState, ProposalNumber, commandsIds, liftFF, payloads}
import mx.cinvestav.utils.v2.PublisherV2
import mx.cinvestav.utils.v2.encoders._
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import mx.cinvestav.commons.events.SavedMetadata
import org.typelevel.log4cats.Logger

import java.util.UUID

object Helpers {

  def sendAccept(proposalNumber: ProposalNumber,value:SavedMetadata,fileId:UUID)(implicit ctx:NodeContext): EitherT[IO, NodeError, Unit] = {

    type E                = NodeError
    val maybeCurrentState = liftFF[NodeState](ctx.state.get)
    implicit val logger   = ctx.logger
    val L                 = Logger.eitherTLogger[IO,E]
//  __________________________________________________________
    val app = for {
      currentState <- maybeCurrentState
      timestamp   <- liftFF[Long](IO.realTime.map(_.toSeconds))
//      _ <- L.info(s"ACCEPT $proposalNumber")
//   ___________________________________________________________________
      paxosNodes       = currentState.paxosPublishers.filter(_._1 != ctx.config.nodeId).values.toList
//   ___________________________________________________________________
      props            = AmqpProperties(
        headers = Map("commandId"->StringVal(commandsIds.ACCEPT)),
        replyTo = ctx.config.nodeId.some
      )
      messagePayload = payloads.Accept(
        proposalNumber = proposalNumber,
        fileId         = fileId.toString,
        value          = value.asJson.noSpaces,
        timestamp      = timestamp
      ).asJson.noSpaces
      message        = AmqpMessage(payload=messagePayload,properties = props)
      _              <- liftFF[Unit](paxosNodes.traverse(_.publish(message)).void)
      _              <- L.debug(s"SENT ACCEPT_VALUE ${paxosNodes.length} ACCEPTORS")
    } yield ()
    app
  }
  def sendPrepare(proposalNumber: ProposalNumber,fileId:UUID)(implicit ctx:NodeContext) = {
    type E                = NodeError
    val maybeCurrentState = liftFF[NodeState](ctx.state.get)
    implicit val logger   = ctx.logger
    val L                 = Logger.eitherTLogger[IO,E]
    val app = for {
      timestamp        <- liftFF[Long](IO.realTime.map(_.toSeconds))
      currentState     <- maybeCurrentState
      paxosNodes       = currentState.paxosPublishers.filter(_._1 != ctx.config.nodeId).values.toList
//    Prepare message
      props            = AmqpProperties(
        headers = Map("commandId"->StringVal(commandsIds.PREPARE)),
        replyTo = ctx.config.nodeId.some
      )
      messagePayload   = payloads.Prepare(
        proposerId     = ctx.config.nodeId,
        proposalNumber = proposalNumber,
        fileId         = fileId.toString,
        timestamp      = timestamp
      ).asJson.noSpaces
      message = AmqpMessage(payload= messagePayload,properties = props)
      //     _________________________________________________________________
      _                <- liftFF[Unit](paxosNodes.traverse(_.publish(message) ).void)
      _                <- L.debug(s"SEND_PREPARE to ${paxosNodes.length} ACCEPTORS")
    } yield ()

    app
  }

}
