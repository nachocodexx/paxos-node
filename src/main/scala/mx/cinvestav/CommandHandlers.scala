/*
 * Copyright (c) 2021- 8/5/2021 by Ignacio Castillo.
 */

package mx.cinvestav

import breeze.linalg.DenseVector
import cats.data.EitherT
import cats.implicits._
import cats.effect._
import dev.profunktor.fs2rabbit.model.AmqpFieldValue.StringVal
import dev.profunktor.fs2rabbit.model.{AmqpEnvelope, AmqpMessage, AmqpProperties}
import mx.cinvestav.Declarations.{LowerProposalNumber, NodeContext, NodeError, NodeState, PaxosError, PaxosNodeDoesNotExist, ProposalNumber, commandsIds, liftFF, payloads}
import mx.cinvestav.utils.v2.{Acker, processMessage}
import mx.cinvestav.utils.v2.encoders._
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import org.typelevel.log4cats.Logger

import java.util.UUID

object CommandHandlers {

  def propose()(implicit ctx:NodeContext,envelope: AmqpEnvelope[String],acker: Acker) = {
    def sucessCallback(acker:Acker,envelope:AmqpEnvelope[String],payload:payloads.Propose) = {
      type E = NodeError
      val maybeCurrentState = EitherT.liftF[IO,E,NodeState](ctx.state.get)
      val unit              = EitherT.liftF[IO,E,Unit](IO.unit)
      implicit val logger   = ctx.logger
      val L                 = Logger.eitherTLogger[IO,E]
      val app = for {
        currentState   <- maybeCurrentState
        _              <- L.info(s"PROPOSE")
        userId         = UUID.fromString(payload.userId)
        fileId         = UUID.fromString(payload.fileId)
        maxProposalIds = currentState.maxProposalIds
        nodeId         = ctx.config.nodeId
        proposalNum    =  maxProposalIds.get(payload.fileId)
            .map(pn=>pn.copy(number=pn.number+1,nodeId =nodeId))
            .getOrElse[ProposalNumber](ProposalNumber(0,nodeId))
//          nodeId = ctx.config.nodeId
//        )
        newProposalNum = (payload.fileId->proposalNum )
        _              <- L.info(s"PROPOSAL_NUMBER $proposalNum")
        _              <- liftFF( ctx.state.update(s=>s.copy( maxProposalIds= maxProposalIds + newProposalNum )) )
        _              <- Helpers.sendPrepare(proposalNumber = proposalNum,fileId=fileId,userId=userId)
      } yield ()
      app.value.flatMap {
        case Left(e) => acker.reject(envelope.deliveryTag) *> ctx.logger.error(e.getMessage)
        case Right(value) => acker.ack(envelope.deliveryTag)
      }
//      ctx.logger.info(s"PROPOSE ${envelope}")*>acker.ack(envelope.deliveryTag)
    }



    processMessage[IO,payloads.Propose,NodeContext](
      successCallback =sucessCallback ,
      errorCallback =  (acker,envelope,e)=>ctx.logger.error(e.getMessage) *> acker.reject(envelope.deliveryTag)
    )
  }

  def promise()(implicit ctx:NodeContext,envelope: AmqpEnvelope[String],acker: Acker) = {
    def sucessCallback(acker:Acker,envelope:AmqpEnvelope[String],payload:payloads.Promise) = {
      ctx.logger.info(s"PROMISE ${envelope}")*>acker.ack(envelope.deliveryTag)
    }
    processMessage[IO,payloads.Promise,NodeContext](
      successCallback =sucessCallback ,
      errorCallback =  (acker,envelope,e)=>ctx.logger.error(e.getMessage) *> acker.reject(envelope.deliveryTag)
    )
  }

  def prepare()(implicit ctx:NodeContext, envelope: AmqpEnvelope[String], acker: Acker) = {

    def sucessCallback(acker:Acker,envelope:AmqpEnvelope[String],payload:payloads.Prepare) = {
////      ________________________________________________________
      //      Bloques(BB), Modelo de procesamiento(Que tiene que hacer), Modelo de comunicacion(Como se va a comunicar con otros BB)
//      Modelo de programacion: Para la creacion
//      Esquemas de manejo : Balanceadores de carga, dispachadores (Adaptar al ambiente)
//      Stack: Es una abstraccion para que los pendejos de lsingenieros no se equivoqyuen.
////      ____________________________________________________________
      type E = NodeError
      val maybeCurrentState = EitherT.liftF[IO,E,NodeState](ctx.state.get)
      val unit              = EitherT.liftF[IO,E,Unit](IO.unit)
      implicit val logger   = ctx.logger
      val L                 = Logger.eitherTLogger[IO,E]
      val app = for {
        timestamp       <- EitherT.liftF[IO,E,Long](IO.realTime.map(_.toSeconds))
        newProposalNum  = payload.proposalNumber
        userId          = UUID.fromString(payload.userId)
        fileId          = UUID.fromString(payload.fileId)
        ////      ____________________________________________________________
        currentState        <- maybeCurrentState
        publishers = currentState.paxosPublishers
        previousProposalNum = currentState.maxProposalIds.getOrElse(payload.fileId,ProposalNumber(Long.MinValue,ctx.config.nodeId))
        ////      ____________________________________________________________
        dontProcess = EitherT.fromEither[IO](Either.left[E,Unit](LowerProposalNumber(newProposalNum,previousProposalNum )  ))
        ////      ____________________________________________________________
        _ <- if (Ordering[ProposalNumber].gt(newProposalNum,previousProposalNum) )
          for {
            //            Send promise to the proposer
            _ <- L.info(s"SEND_PROMISE TO THE PROPOSER(${payload.proposerId})")
            props   = AmqpProperties(headers = Map("commandId"->StringVal(commandsIds.PROMISE)))
            messagePayload   = payloads.Promise(
              nodeId         = ctx.config.nodeId,
              proposalNumber = payload.proposalNumber,
              fileId         = fileId.toString,
              timestamp      = timestamp
            ).asJson.noSpaces
            message = AmqpMessage(payload= messagePayload,properties = props)
          } yield ()

         else   dontProcess
      } yield ( )

      app .value.flatMap {
        case Left(e) => e match {
          case error: PaxosError => error match {
            case e@LowerProposalNumber(newProposal, oldProposal) =>
              acker.reject(envelope.deliveryTag) *> ctx.logger.error(e.getMessage)
            case _ =>  acker.reject(envelope.deliveryTag)*>ctx.logger.error(e.getMessage)
          }
          case e => ctx.logger.error(s"UKNOWN ERROR: ${e.getMessage}")*>acker.reject(envelope.deliveryTag)
        }
        case Right(value) => ctx.logger.info("PREPARE_DONE")*>acker.ack(envelope.deliveryTag)
      }
    }

    processMessage[IO,payloads.Prepare,NodeContext](
      successCallback =sucessCallback ,
      errorCallback =  (acker,envelope,e)=>ctx.logger.error(e.getMessage) *> acker.reject(envelope.deliveryTag)
//      (AckResult.Reject(envelope.deliveryTag))
    )
  }


}
