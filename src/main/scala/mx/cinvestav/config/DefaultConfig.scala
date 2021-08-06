package mx.cinvestav.config


import mx.cinvestav.Declarations.PaxosNode
case class DefaultConfig(
                          nodeId:String,
                          poolId:String,
                          paxosNodes:List[PaxosNode],
                          rabbitmq: RabbitMQConfig
                        )
