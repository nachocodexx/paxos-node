/*
 * Copyright (c)  8/5/2021 by Ignacio Castillo.
 */

package mx.cinvestav

import breeze.linalg.DenseVector
import cats.effect.{ExitCode, IO, IOApp}
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.model.{AckResult, AmqpFieldValue, ExchangeName, QueueName, RoutingKey}
import mx.cinvestav.Declarations.{NodeContext, NodeState, RabbitContext, commandsIds}
import mx.cinvestav.config.DefaultConfig
import mx.cinvestav.utils.RabbitMQUtils
import mx.cinvestav.utils.v2.{Acker, Exchange, MessageQueue, processMessage}
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.generic.auto._
import pureconfig.ConfigSource
import breeze.linalg._
import cats.effect.std.Queue
import mx.cinvestav.commons.events.Event

object Main extends IOApp{
  implicit val config: DefaultConfig           = ConfigSource.default.loadOrThrow[DefaultConfig]
  implicit val rabbitMqConfig: Fs2RabbitConfig = RabbitMQUtils.dynamicRabbitMQConfig(config.rabbitmq)
  implicit val unsafeLogger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  def mainProgram(queueName: QueueName)(implicit ctx:NodeContext):IO[Unit] = {
    val connection = ctx.rabbitContext.connection
    val client = ctx.rabbitContext.client
    client.createChannel(connection) .use{implicit  channel =>
      for {
        _                 <- ctx.logger.info("CONSUMMING")
        (_acker,consumer) <- ctx.rabbitContext.client.createAckerConsumer(queueName = queueName)
        _ <- consumer.evalMap{ implicit envelope=>
          val maybeCommandId = envelope.properties.headers.get("commandId")
          implicit val  acker:Acker  = Acker(_acker)
          maybeCommandId match {
            case Some(commandId) =>
              commandId match {
                case AmqpFieldValue.StringVal(value) if value == commandsIds.PROPOSE => CommandHandlers.propose()
                case AmqpFieldValue.StringVal(value) if value == commandsIds.PREPARE => CommandHandlers.prepare()
                case AmqpFieldValue.StringVal(value) if value == "PROMISE" => CommandHandlers.promise()
              }
            case None =>
              for{
                _ <- ctx.logger.error("NO COMMAND_ID PROVIED")
                _ <- acker.reject(envelope.deliveryTag)
              } yield ()
          }
        }.compile.drain
      } yield ()
    }
//    IO.unit
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val mainQueueName = QueueName(s"${config.poolId}-${config.nodeId}")
    val exchangeName:ExchangeName = ExchangeName(s"${config.poolId}")
    val routingKey = RoutingKey(s"${config.poolId}.${config.nodeId}")
    import mx.cinvestav.utils.v2.{PublisherConfig,PublisherV2}
    RabbitMQUtils.initV2[IO](rabbitMqConfig){ implicit client =>
      client.createConnection.use { implicit connection=>
        for {
          _ <- Logger[IO].info("PAXOS NODE STARTED...")
          //        _____________________________________________________
          rabbitContext    = RabbitContext(client = client,connection=connection)
          //        _____________________________________________________
          q <- Queue.bounded[IO,Event](capacity = 100)
          //        _____________________________________________________
          paxosPublishers = config
            .paxosNodes
            .map(pn=>
              (pn.nodeId,PublisherConfig(exchangeName = exchangeName,routingKey = RoutingKey(s"${config.poolId}.${pn.nodeId}")))
            ).toMap.map{
            case (paxosNodeId, config) => (paxosNodeId,PublisherV2(config))
            }
//            .map((_.,PublisherV2(_)))
          //        _____________________________________________________
          initState    = NodeState(
            paxosNode = config.paxosNodes,
            eventLog = q,
            paxosPublishers = paxosPublishers
          )
          _ <- Logger[IO].info(initState.toString)
          state <- IO.ref(initState)
          //        _____________________________________________________
          ctx  = NodeContext(config=config,logger=unsafeLogger,rabbitContext = rabbitContext,state=state)
          //        _____________________________________________________
          _            <- Exchange.topic(
            exchangeName = exchangeName,
          )
          //       ____________________________________
          _              <- MessageQueue.createThenBind(
            queueName    = mainQueueName,
            exchangeName = exchangeName,
            routingKey   = routingKey
          )
          //
          //        _____________________________________________________
          _ <- mainProgram(queueName = mainQueueName)(ctx=ctx)
        } yield ()
      }
    }.as(ExitCode.Success)
//    IO.unit.as(ExitCode.Success)
  }
}
