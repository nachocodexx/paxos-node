import sbt._


object Dependencies {
  def apply(): Seq[ModuleID] = {
    lazy val RabbitMQUtils = "mx.cinvestav" %% "rabbitmq-utils" % "0.3.3"
    lazy val Commons = "mx.cinvestav" %% "commons" % "0.0.5"
    lazy val PureConfig = "com.github.pureconfig" %% "pureconfig" % "0.15.0"
    lazy val MUnitCats ="org.typelevel" %% "munit-cats-effect-3" % "1.0.3" % Test
    lazy val Log4Cats =   "org.typelevel" %% "log4cats-slf4j"   % "2.1.1"
    Seq(RabbitMQUtils,PureConfig,Commons,MUnitCats,Log4Cats)
  }
}


