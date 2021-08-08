/*
 * Copyright (c) 2021- 8/5/2021 by Ignacio Castillo.
 */

package mx.cinvestav
import cats.data.EitherT
import cats.implicits._
import cats.effect._
import dev.profunktor.fs2rabbit.model.AmqpFieldValue.StringVal
import dev.profunktor.fs2rabbit.model.{AmqpEnvelope, AmqpMessage, AmqpProperties}
import mx.cinvestav.Declarations.{DecodedValueError, LowerProposalNumber, NodeContext, NodeError, NodeState, PaxosError, PaxosNodeNotFound, ProposalNumber, ProposedValueNotFound, PublisherNotFound, ReplyToPropertyNotFound, commandsIds, liftFF, payloads}
import mx.cinvestav.utils.v2.{Acker, processMessage}
import mx.cinvestav.utils.v2.encoders._
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import mx.cinvestav.commons.events.SavedMetadata
import mx.cinvestav.commons.stopwatch.StopWatch._
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.collection.mutable

object CommandHandlers {

  def accepted()(implicit ctx:NodeContext,envelope: AmqpEnvelope[String],acker: Acker): IO[Unit] = {

    def successCallback(akcer:Acker,envelope: AmqpEnvelope[String],payload:payloads.Accepted)=  {

      type E                = NodeError
      val maybeCurrentState = EitherT.liftF[IO,E,NodeState](ctx.state.get)
      implicit val logger   = ctx.logger
      val L                 = Logger.eitherTLogger[IO,E]
      val app = for {
        _ <- L.debug(s"ACCEPTED ${payload.proposalNumber} ${payload.value}")
        _ <- L.debug("SEND COMMAND TO CHORD SYSTEM")
      } yield ()

      app.value.stopwatch.flatMap { result =>
        result.result match {
          case Left(e) => acker.reject(envelope.deliveryTag) *> ctx.logger.error(e.getMessage)
          case Right(value) => for {
              _ <- acker.ack(envelope.deliveryTag)
              _ <- ctx.logger.info(s"ACCEPT ${payload.proposalNumber.number} ${payload.fileId} ${result.duration}")
            } yield ()
        }

      }
    }
    processMessage[IO,payloads.Accepted,NodeContext](
      successCallback =successCallback ,
      errorCallback =  (acker,envelope,e)=>ctx.logger.error(e.getMessage) *> acker.reject(envelope.deliveryTag)
    )
  }
  def accept()(implicit ctx:NodeContext,envelope: AmqpEnvelope[String],acker: Acker): IO[Unit] = {
    def successCallback(akcer:Acker,envelope: AmqpEnvelope[String],payload:payloads.Accept)=  {
      type E = NodeError
      val maybeCurrentState = EitherT.liftF[IO,E,NodeState](ctx.state.get)
      val maybeReplyTo    = EitherT.fromEither[IO](envelope.properties.replyTo.toRight[E]{ReplyToPropertyNotFound()})
      val unit              = EitherT.liftF[IO,E,Unit](IO.unit)
      implicit val logger   = ctx.logger
      val L                 = Logger.eitherTLogger[IO,E]
      val app = for {
        timestamp         <- liftFF[Long](IO.realTime.map(_.toSeconds))
//        _                 <- L.info(s"ACCEPT ${payload.proposalNumber} ${payload.value}")
        currentState      <- maybeCurrentState
        eventLog          = currentState.eventLog
        replyTo           <- maybeReplyTo
        proposer          <- EitherT.fromEither[IO](currentState.paxosPublishers.get(replyTo).toRight{PublisherNotFound(replyTo)})
        maxProposalIds    = currentState.maxProposalIds
        proposalNumber    = payload.proposalNumber
        fileId            = payload.fileId
        fileIdUUID        = UUID.fromString(fileId)
        minProposalNumber = ProposalNumber(Long.MinValue, proposalNumber.nodeId)
        maxProposalNumber =  maxProposalIds.getOrElse(fileId, minProposalNumber)
        predicate         = Ordering[ProposalNumber].gt(proposalNumber,maxProposalNumber)
        proposedValue     <- EitherT
          .fromEither[IO](
            parser.parse(payload.value).flatMap(_.as[SavedMetadata])
          )
          .leftMap(e=>DecodedValueError(e.getMessage))
//      __________________________________________________________________________________________
//        _                 <- L.info(s"MAX_PROPOSAL_NUMBER $maxProposalNumber  / PROPOSAL_NUMBER  $proposalNumber")
        _                 <- if(predicate)
          for {
//          _           <- L.info(s"ACCEPT THE VALUE ${payload.value}")
            _ <- unit
//        __________________________________________________________________
          newEventLog = eventLog.enqueue(proposedValue)
          _ <- liftFF[Unit](ctx.state.update(
            s=>s.copy(
                eventLog =  newEventLog ,
                maxProposalIds =  s.maxProposalIds + (payload.fileId->proposalNumber)
            )
            )
          )
//        __________________________________________________________________
          acceptedPayload = payloads.Accepted(proposalNumber = proposalNumber,fileId =fileId,value=payload.value,timestamp = timestamp).asJson.noSpaces
          acceptedProps   = AmqpProperties(headers = Map("commandId"->StringVal(commandsIds.ACCEPTED)))
          acceptedMsg     = AmqpMessage(payload=acceptedPayload,properties = acceptedProps)
          _               <- liftFF[Unit](proposer.publish(acceptedMsg))
//            _             <- liftFF[Unit]()
        } yield ()
//        REJECT AND SEND MOST RECENT VALUE AND MAX_PROPOSAL_NUMBER
        else L.info("REJECT THE VALUE")
      } yield ()
      app.value.stopwatch.flatMap { result =>
        result.result match{
          case Left(e) => acker.reject(envelope.deliveryTag) *> ctx.logger.error(e.getMessage)
          case Right(value) => for {
             _ <- acker.ack(envelope.deliveryTag)
             _ <- ctx.logger.info(s"ACCEPT ${payload.proposalNumber.number} ${payload.fileId} ${result.duration}")
          } yield ()
        }
      }
    }

    processMessage[IO,payloads.Accept,NodeContext](
      successCallback =successCallback ,
      errorCallback =  (acker,envelope,e)=>ctx.logger.error(e.getMessage) *> acker.reject(envelope.deliveryTag)
    )
  }
  def propose()(implicit ctx:NodeContext,envelope: AmqpEnvelope[String],acker: Acker): IO[Unit] = {
    def sucessCallback(acker:Acker,envelope:AmqpEnvelope[String],payload:payloads.Propose) = {
      type E = NodeError
      val maybeCurrentState = EitherT.liftF[IO,E,NodeState](ctx.state.get)
      implicit val logger: Logger[IO] = ctx.logger
      val L                 = Logger.eitherTLogger[IO,E]
      val app= for {
//      Current state
        currentState   <- maybeCurrentState
        fileId         = UUID.fromString(payload.fileId)
        maxProposalIds = currentState.maxProposalIds
        nodeId         = ctx.config.nodeId
//      Generate a proposal number based on the max proposal seen so far
        proposalNum    =  maxProposalIds.get(payload.fileId)
            .map(pn=>pn.copy(number=pn.number+1,nodeId =nodeId))
            .getOrElse[ProposalNumber](ProposalNumber(0,nodeId))
        newProposalNum   = (payload.fileId->proposalNum )
        decodedValue     <- EitherT
          .fromEither[IO](
            parser.parse(payload.value).flatMap(_.as[SavedMetadata])
          )
          .leftMap(e=>DecodedValueError(e.getMessage))
        newProposedValue = (proposalNum -> decodedValue)
//
        proposedValues   = currentState.proposedValues
//
        _                <- L.debug(s"NEW_PROPOSAL $proposalNum $fileId")
        _                <- liftFF( ctx.state.update(s=>
          s.copy(
            maxProposalIds = maxProposalIds + newProposalNum ,
            proposedValues =  proposedValues + newProposedValue
          ))
        )
//        Send prepare to all the aceptors
        _              <- Helpers.sendPrepare(proposalNumber = proposalNum,fileId=fileId)

      } yield proposalNum

//      val x= app.value.stopwatch
       app.value.stopwatch.flatMap{ result =>
         result.result match {
             case Left(e) => acker.reject(envelope.deliveryTag) *> ctx.logger.error(e.getMessage)
             case Right(value) => for {
                _ <- acker.ack(envelope.deliveryTag)
               _ <- ctx.logger.info(s"PROPOSE ${value.number} ${payload.fileId} ${result.duration}")
             } yield ( )
//              ctx.logger.info(s"STOPWATCH ${result.duration}") *>
         }
      }
    }



    processMessage[IO,payloads.Propose,NodeContext](
      successCallback =sucessCallback ,
      errorCallback =  (acker,envelope,e)=>ctx.logger.error(e.getMessage) *> acker.reject(envelope.deliveryTag)
    )
  }

  def promise()(implicit ctx:NodeContext,envelope: AmqpEnvelope[String],acker: Acker): IO[Unit] = {
    def sucessCallback(acker:Acker,envelope:AmqpEnvelope[String],payload:payloads.Promise) = {
      type E = NodeError
      val maybeCurrentState = EitherT.liftF[IO,E,NodeState](ctx.state.get)
      val unit              = EitherT.liftF[IO,E,Unit](IO.unit)
      implicit val logger: Logger[IO] = ctx.logger
      val L                 = Logger.eitherTLogger[IO,E]
//      ________________________________________________________________________________________
      val app = for {
        _                  <- L.debug(s"PROMISE ${payload.nodeId} ${payload.proposalNumber} ${payload.relativeValue}")
        currentState       <- maybeCurrentState
//      ________________________________________________________________________________________
        proposalNumber     = payload.proposalNumber
        paxosNodes         = currentState.paxosNode
        promisesCounter    = currentState.promisesCounter
        promiseCounter     = promisesCounter.get(payload.proposalNumber).map(_+1).getOrElse(1)
        newProposesCounter = promisesCounter + (payload.proposalNumber -> promiseCounter)
        fileId             = UUID.fromString(payload.fileId)
        proposedValues     = currentState.proposedValues
//      ________________________________________________________________________________________
        _                  <- L.debug(s"PROMISE_COUNTER $promiseCounter")
        _                  <- liftFF[Unit](ctx.state.update(s=>s.copy(promisesCounter = newProposesCounter )))
//      ________________________________________________________________________________________
        predicate          = promiseCounter >= paxosNodes.length-1
        _                  <- if(predicate)
          for {
          _     <- unit
          value <-  EitherT.fromEither[IO](proposedValues.get(proposalNumber).toRight{ProposedValueNotFound(payload.proposalNumber)})
          _     <- L.debug(s"SEND ACCEPT TO ${paxosNodes.length-1} Aceptors")
          _     <- liftFF[Unit](ctx.state.update(s=>s.copy( proposedValues = proposedValues - proposalNumber  ) ))
          _     <- Helpers.sendAccept(proposalNumber,value,fileId)
        } yield ( )
        else L.debug(s"${paxosNodes.length - promiseCounter} ACEPTORS LEFT")
//      ________________________________________________________________________________________
      } yield()
//      ________________________________________________________________________________________
      app.value.stopwatch.flatMap { result=>
        result.result match {
          case Left(e) => acker.reject(envelope.deliveryTag) *> ctx.logger.error(e.getMessage)
          case Right(value) => for {
             _ <- acker.ack(envelope.deliveryTag)
            _  <- ctx.logger.info(s"PROMISE ${payload.proposalNumber.number} ${payload.fileId} ${result.duration}")
          } yield ()
        }
      }
//      ctx.logger.info(s"PROMISE ${envelope}")*>acker.ack(envelope.deliveryTag)
    }
    processMessage[IO,payloads.Promise,NodeContext](
      successCallback =sucessCallback ,
      errorCallback =  (acker,envelope,e)=>ctx.logger.error(e.getMessage) *> acker.reject(envelope.deliveryTag)
    )
  }

  def prepare()(implicit ctx:NodeContext, envelope: AmqpEnvelope[String], acker: Acker): IO[Unit] = {

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
      val maybeReplyTo    = EitherT.fromEither[IO](envelope.properties.replyTo.toRight[E]{ReplyToPropertyNotFound()})
      implicit val logger   = ctx.logger
      val L                 = Logger.eitherTLogger[IO,E]
//
      val app = for {
        timestamp       <- EitherT.liftF[IO,E,Long](IO.realTime.map(_.toSeconds))
        newProposalNum  = payload.proposalNumber
        fileId          = UUID.fromString(payload.fileId)
        ////      ____________________________________________________________
        currentState        <- maybeCurrentState
        ////      ____________________________________________________________
        replyTo             <- maybeReplyTo
        publishers          = currentState.paxosPublishers
        proposer            <- EitherT.fromEither[IO](publishers.get(replyTo).toRight{PublisherNotFound(replyTo)})
        minProposalNumber   = ProposalNumber(Long.MinValue,newProposalNum.nodeId)
        maxProposalNumber   = currentState.maxProposalIds.getOrElse(payload.fileId,  minProposalNumber)
        ////      ____________________________________________________________
        dontProcess         = EitherT.fromEither[IO](Either.left[E,Unit](LowerProposalNumber(newProposalNum,maxProposalNumber )  ))
        ////      ____________________________________________________________
        isGreater = Ordering[ProposalNumber].gt(newProposalNum,maxProposalNumber)
        _ <- if (isGreater)
          for {
            //            Send promise to the proposer
            _        <- L.debug(s"SEND_PROMISE TO THE PROPOSER(${payload.proposerId})")
//          __________________________________________________________________________________________________
            eventLog = currentState.eventLog
            props    = AmqpProperties(
              headers = Map("commandId"->StringVal(commandsIds.PROMISE)),
              replyTo = ctx.config.nodeId.some
            )
            messagePayload   =(relativeValue:String)=> payloads.Promise(
              nodeId         = ctx.config.nodeId,
              proposalNumber = payload.proposalNumber,
              fileId         = fileId.toString,
              relativeValue  = relativeValue ,
              timestamp      = timestamp
            ).asJson.noSpaces
            message          = (relativeValue:String) => AmqpMessage(payload= messagePayload(relativeValue),properties = props)
//          __________________________________________________________________________________________________
//          Get the most recent accepted value
              mostRecentValue  = eventLog.filter{
                            case sm: SavedMetadata =>  if(sm.fileId == fileId ) true else false
                            case _ => false
              }.lastOption.asInstanceOf[ Option[SavedMetadata] ]
            _ <- L.debug(s"MOST_RECENT_VALUE $mostRecentValue")
//           Check if exists a record in the event log
            _ <- mostRecentValue match {
              case Some(value) =>  for {
                _ <- L.debug(s"SEND_PROMISE ${replyTo} $value")
                _ <- liftFF[Unit](proposer.publish(message(value.asJson.noSpaces)))
              } yield ()
              case None => for {
                _ <- L.debug(s"SEND_PROMISE ${replyTo} NO_VALUE")
                _ <- liftFF[Unit](proposer.publish(  message("")  ))
              } yield ()
            }
          } yield ()

         else   dontProcess
      } yield ( )

      app.value.stopwatch.flatMap {result =>
        result.result match {
                  case Left(e) => e match {
                    case error: PaxosError => error match {
                      case e@LowerProposalNumber(newProposal, oldProposal) =>
                        acker.reject(envelope.deliveryTag) *> ctx.logger.error(e.getMessage)
                      case _ =>  acker.reject(envelope.deliveryTag)*>ctx.logger.error(e.getMessage)
                    }
                    case e => ctx.logger.error(s"UKNOWN ERROR: ${e.getMessage}")*>acker.reject(envelope.deliveryTag)
                  }
                  case Right(value) => for {
                    _ <- ctx.logger.info(s"PREPARE ${payload.proposalNumber.number} ${payload.fileId} ${result.duration}")
                    _ <- acker.ack(envelope.deliveryTag)
                  } yield ()
        }
      }
    }

    processMessage[IO,payloads.Prepare,NodeContext](
      successCallback =sucessCallback ,
      errorCallback =  (acker,envelope,e)=>ctx.logger.error(e.getMessage) *> acker.reject(envelope.deliveryTag)
//      (AckResult.Reject(envelope.deliveryTag))
    )
  }


}
