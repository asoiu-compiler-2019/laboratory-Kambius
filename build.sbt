addCommandAlias("validate", ";scalafmtCheck;scalafmtSbtCheck;test")

val silencerV        = "1.3.1"
val logbackV         = "1.2.3"
val catsV            = "1.6.0"
val scalatestV       = "3.0.6"
val akkaV            = "2.5.21"
val circeV           = "0.11.1"
val akkaHttpV        = "10.1.7"
val h2V              = "1.4.198"
val scalikejdbcV     = "3.3.3"
val kamonCoreV       = "1.1.5"
val kamonPrometheusV = "1.1.1"
val akkaHttpCirceV   = "1.25.2"
val fastparceV       = "2.1.0"
val kindProjectorV   = "0.9.9"

lazy val commonSettings = Seq(
  scalaVersion := "2.12.8",
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:postfixOps",
    "-Yno-adapted-args",
    "-Ypartial-unification",
    "-Ywarn-dead-code",
    "-Ywarn-extra-implicit",
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-Ywarn-unused:_",
    "-Xfatal-warnings",
    "-Xfuture",
    "-Xlint:_"
  ),
  libraryDependencies ++= Seq(
    "org.typelevel"   %% "cats-core"     % catsV,
    "org.scalatest"   %% "scalatest"     % scalatestV % Test,
    "com.github.ghik" %% "silencer-lib"  % silencerV % Provided,
    "com.lihaoyi"     %% "pprint"        % "0.5.3",
    "io.circe"        %% "circe-core"    % circeV,
    "io.circe"        %% "circe-generic" % circeV,
    "io.circe"        %% "circe-parser"  % circeV,
  ),
  libraryDependencies ++= Seq(
    compilerPlugin("org.spire-math"  %% "kind-projector"  % kindProjectorV),
    compilerPlugin("com.github.ghik" %% "silencer-plugin" % silencerV)
  )
)

lazy val storageSettings = Seq(
  name := "storage",
  version := "0.1",
  libraryDependencies ++= Seq(
    "ch.qos.logback"    % "logback-core"        % logbackV,
    "ch.qos.logback"    % "logback-classic"     % logbackV,
    "com.typesafe.akka" %% "akka-actor-typed"   % akkaV,
    "com.typesafe.akka" %% "akka-cluster-typed" % akkaV,
    "com.typesafe.akka" %% "akka-slf4j"         % akkaV,
    "com.typesafe.akka" %% "akka-http"          % akkaHttpV,
    "com.h2database"    % "h2"                  % h2V,
    "org.scalikejdbc"   %% "scalikejdbc"        % scalikejdbcV,
    "de.heikoseeberger" %% "akka-http-circe"    % akkaHttpCirceV,
    "io.kamon"          %% "kamon-core"         % kamonCoreV,
    "io.kamon"          %% "kamon-prometheus"   % kamonPrometheusV
  )
)

lazy val storage = project
  .in(file("storage"))
  .settings(commonSettings)
  .settings(storageSettings)
  .enablePlugins(JavaAppPackaging)

lazy val idmlSettings = Seq(
  name := "hdml",
  version := "0.1",
  libraryDependencies ++= Seq(
    "com.lihaoyi" %% "fastparse" % fastparceV
  )
)

lazy val hdml = project
  .in(file("hdml"))
  .settings(commonSettings)
  .settings(idmlSettings)

lazy val replicantProjects = Seq(
  storage,
  hdml
)

lazy val replicant = project
  .in(file("."))
  .settings(commonSettings)
  .dependsOn(replicantProjects.map(p => p: ClasspathDep[ProjectReference]): _*)
  .aggregate(replicantProjects.map(p => p: ProjectReference): _*)
