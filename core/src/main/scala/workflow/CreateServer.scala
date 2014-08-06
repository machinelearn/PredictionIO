package io.prediction.workflow

import io.prediction.controller.IEngineFactory
import io.prediction.controller.EmptyParams
import io.prediction.controller.Engine
import io.prediction.controller.PAlgorithm
import io.prediction.controller.Params
import io.prediction.controller.java.LJavaAlgorithm
import io.prediction.core.BaseAlgorithm
import io.prediction.core.BaseServing
import io.prediction.core.Doer
import io.prediction.storage.{ Storage, EngineManifest, Run }

import akka.actor.{ Actor, ActorRef, ActorSystem, Kill, Props }
import akka.event.Logging
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.google.gson.Gson
import com.twitter.chill.KryoInjection
import com.twitter.chill.ScalaKryoInstantiator
import grizzled.slf4j.Logging
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization.{ read, write }
import spray.can.Http
import spray.routing._
import spray.http._
import spray.http.MediaTypes._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.existentials
import scala.reflect.runtime.universe

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.net.URLClassLoader

class KryoInstantiator(classLoader: ClassLoader) extends ScalaKryoInstantiator {
  override def newKryo = {
    val kryo = super.newKryo
    kryo.setClassLoader(classLoader)
    kryo
  }
}

case class ServerConfig(
  runId: String = "",
  engineId: Option[String] = None,
  engineVersion: Option[String] = None,
  ip: String = "localhost",
  port: Int = 8000,
  sparkMaster: String = "local",
  sparkExecutorMemory: String = "4g")

case class StartServer()
case class StopServer()
case class ReloadServer()

object CreateServer extends Logging {
  val actorSystem = ActorSystem("pio-server")
  val runs = Storage.getMetaDataRuns
  val engineManifests = Storage.getMetaDataEngineManifests

  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[ServerConfig]("CreateServer") {
      opt[String]("engineId") action { (x, c) =>
        c.copy(engineId = Some(x))
      } text("Engine ID.")
      opt[String]("engineVersion") action { (x, c) =>
        c.copy(engineVersion = Some(x))
      } text("Engine version.")
      opt[String]("ip") action { (x, c) =>
        c.copy(ip = x)
      } text("IP to bind to (default: localhost).")
      opt[Int]("port") action { (x, c) =>
        c.copy(port = x)
      } text("Port to bind to (default: 8000).")
      opt[String]("sparkMaster") action { (x, c) =>
        c.copy(sparkMaster = x)
      } text("Apache Spark master URL (default: local).")
      opt[String]("sparkExecutorMemory") action { (x, c) =>
        c.copy(sparkExecutorMemory = x)
      } text("Apache Spark executor memory (default: 4g)")
      opt[String]("runId") required() action { (x, c) =>
        c.copy(runId = x)
      } text("Run ID.")
    }

    parser.parse(args, ServerConfig()) map { sc =>
      runs.get(sc.runId) map { run =>
        val engineId = sc.engineId.getOrElse(run.engineId)
        val engineVersion = sc.engineVersion.getOrElse(run.engineVersion)
        engineManifests.get(engineId, engineVersion) map { manifest =>
          WorkflowUtils.checkUpgrade("deployment")
          val engineFactoryName = manifest.engineFactory
          val master = actorSystem.actorOf(Props(
            classOf[MasterActor],
            sc,
            run,
            engineFactoryName,
            manifest),
          "master")
          implicit val timeout = Timeout(5.seconds)
          master ? StartServer()
        } getOrElse {
          error(s"Invalid Engine ID or version. Aborting server.")
        }
      } getOrElse {
        error(s"Invalid Run ID. Aborting server.")
      }
    }
  }

  def createServerActorWithEngine[TD, DP, PD, Q, P, A](
    sc: ServerConfig,
    run: Run,
    engine: Engine[TD, DP, PD, Q, P, A],
    engineLanguage: EngineLanguage.Value,
    manifest: EngineManifest): ActorRef = {
    implicit val formats = DefaultFormats

    val algorithmsParamsWithNames =
      read[Seq[(String, JValue)]](run.algorithmsParams).map {
        case (algoName, params) =>
          val extractedParams = WorkflowUtils.extractParams(
            engineLanguage,
            compact(render(params)),
            engine.algorithmClassMap(algoName))
          (algoName, extractedParams)
      }

    val algorithmsParams = algorithmsParamsWithNames.map { _._2 }

    val algorithms = algorithmsParamsWithNames.map { case (n, p) =>
      Doer(engine.algorithmClassMap(n), p)
    }

    val servingParams =
      if (run.servingParams == "")
        EmptyParams()
      else
        WorkflowUtils.extractParams(
          engineLanguage,
          run.servingParams,
          engine.servingClass)
    val serving = Doer(engine.servingClass, servingParams)

    val pAlgorithmExists =
      algorithms.exists(_.isInstanceOf[PAlgorithm[_, PD, _, Q, P]])
    val sparkContext =
      if (pAlgorithmExists) Some(WorkflowContext(run.batch, run.env)) else None
    val evalPreparedMap = sparkContext map { sc =>
      logger.info("Data Source")
      val dataSourceParams = WorkflowUtils.extractParams(
        engineLanguage,
        run.dataSourceParams,
        engine.dataSourceClass)
      val dataSource = Doer(engine.dataSourceClass, dataSourceParams)
      val evalParamsDataMap
      : Map[EI, (DP, TD, RDD[(Q, A)])] = dataSource
        .readBase(sc)
        .zipWithIndex
        .map(_.swap)
        .toMap
      val evalDataMap: Map[EI, (TD, RDD[(Q, A)])] = evalParamsDataMap.map {
        case(ei, e) => (ei -> (e._2, e._3))
      }
      logger.info("Preparator")
      val preparatorParams = WorkflowUtils.extractParams(
        engineLanguage,
        run.preparatorParams,
        engine.preparatorClass)
      val preparator = Doer(engine.preparatorClass, preparatorParams)

      val evalPreparedMap: Map[EI, PD] = evalDataMap
      .map{ case (ei, data) => (ei, preparator.prepareBase(sc, data._1)) }
      logger.info("Preparator complete")

      evalPreparedMap
    }

    val kryoInstantiator = new KryoInstantiator(getClass.getClassLoader)
    val kryo = KryoInjection.instance(kryoInstantiator)
    val modelsFromRun = kryo.invert(run.models).get.asInstanceOf[Seq[Seq[Any]]]
    val models = modelsFromRun.head.zip(algorithms).zip(algorithmsParams).map {
      case ((m, a), p) =>
        if (m.isInstanceOf[PersistentModelManifest]) {
          info("Custom-persisted model detected for algorithm " +
            a.getClass.getName)
          WorkflowUtils.getPersistentModel(
            m.asInstanceOf[PersistentModelManifest],
            run.id,
            p,
            sparkContext,
            getClass.getClassLoader)
        } else if (a.isInstanceOf[PAlgorithm[_, _, _, Q, P]]) {
          info(s"Parallel model detected for algorithm ${a.getClass.getName}")
          a.trainBase(sparkContext.get, evalPreparedMap.get(0))
        } else {
          try {
            info(s"Loaded model ${m.getClass.getName} for algorithm " +
              s"${a.getClass.getName}")
            m
          } catch {
            case e: NullPointerException =>
              warn(s"Null model detected for algorithm ${a.getClass.getName}")
              m
          }
        }
    }

    actorSystem.actorOf(
      Props(
        classOf[ServerActor[Q, P]],
        sc,
        run,
        engine,
        engineLanguage,
        manifest,
        algorithms,
        algorithmsParams,
        models,
        serving,
        servingParams))
  }
}

class MasterActor(
    sc: ServerConfig,
    run: Run,
    engineFactoryName: String,
    manifest: EngineManifest) extends Actor {
  val log = Logging(context.system, this)
  implicit val system = context.system
  var sprayHttpListener: Option[ActorRef] = None
  var currentServerActor: Option[ActorRef] = None
  def receive = {
    case x: StartServer =>
      val actor = createServerActor(sc, run, engineFactoryName, manifest)
      IO(Http) ! Http.Bind(actor, interface = sc.ip, port = sc.port)
      currentServerActor = Some(actor)
    case x: StopServer =>
      log.info(s"Stop server command received.")
      sprayHttpListener.map { l =>
        log.info("Server is shutting down.")
        l ! Http.Unbind(5.seconds)
        system.shutdown
      } getOrElse {
        log.warning("No active server is running.")
      }
    case x: ReloadServer =>
      log.info("Reload server command received.")
      val latestRun = CreateServer.runs.getLatestCompleted(
        manifest.id,
        manifest.version)
      latestRun map { lr =>
        val actor = createServerActor(sc, lr, engineFactoryName, manifest)
        sprayHttpListener.map { l =>
          l ! Http.Unbind(5.seconds)
          IO(Http) ! Http.Bind(actor, interface = sc.ip, port = sc.port)
          currentServerActor.get ! Kill
          currentServerActor = Some(actor)
        } getOrElse {
          log.warning("No active server is running. Abort reloading.")
        }
      } getOrElse {
        log.warning(
          s"No latest completed run for ${manifest.id} ${manifest.version}. " +
          "Abort reloading.")
      }
    case x: Http.Bound =>
      log.info("Bind successful. Ready to serve.")
      sprayHttpListener = Some(sender)
    case x: Http.CommandFailed =>
      log.error("Bind failed. Shutting down.")
      system.shutdown
  }

  def createServerActor(
      sc: ServerConfig,
      run: Run,
      engineFactoryName: String,
      manifest: EngineManifest): ActorRef = {
    val (engineLanguage, engine) =
      WorkflowUtils.getEngine(engineFactoryName, getClass.getClassLoader)
    CreateServer.createServerActorWithEngine(
      sc,
      run,
      engine,
      engineLanguage,
      manifest)
  }
}

class ServerActor[Q, P](
    val args: ServerConfig,
    val run: Run,
    val engine: Engine[_, _, _, Q, P, _],
    val engineLanguage: EngineLanguage.Value,
    val manifest: EngineManifest,
    val algorithms: Seq[BaseAlgorithm[_ <: Params, _, _, Q, P]],
    val algorithmsParams: Seq[Params],
    val models: Seq[Any],
    val serving: BaseServing[_ <: Params, Q, P],
    val servingParams: Params) extends Actor with HttpService {

  lazy val gson = new Gson

  def actorRefFactory = context

  def receive = runRoute(myRoute)

  val myRoute =
    path("") {
      get {
        respondWithMediaType(`text/html`) {
          complete {
            <html>
              <head>
                <title>{manifest.id} {manifest.version} - PredictionIO Server at {args.ip}:{args.port}</title>
              </head>
              <body>
                <h1>PredictionIO Server at {args.ip}:{args.port}</h1>
                <div>
                  <ul>
                    <li><strong>Run ID:</strong> {run.id}</li>
                    <li><strong>Run Start Time:</strong> {run.startTime}</li>
                    <li><strong>Run End Time:</strong> {run.endTime}</li>
                    <li><strong>Engine:</strong> {manifest.id} {manifest.version}</li>
                    <li><strong>Class:</strong> {manifest.engineFactory}</li>
                    <li><strong>Files:</strong> {manifest.files.mkString(" ")}</li>
                    <li><strong>Algorithms:</strong> {algorithms.mkString(" ")}</li>
                    <li><strong>Algorithms Parameters:</strong> {algorithmsParams.mkString(" ")}</li>
                    <li><strong>Models:</strong> {models.mkString(" ")}</li>
                    <li><strong>Server Parameters:</strong> {servingParams}</li>
                  </ul>
                </div>
              </body>
            </html>
          }
        }
      } ~
      post {
        entity(as[String]) { queryString =>
          val firstAlgorithm = algorithms.head
          val query =
            if (firstAlgorithm.isInstanceOf[LJavaAlgorithm[_, _, _, Q, P]]) {
              gson.fromJson(
                queryString,
                firstAlgorithm.asInstanceOf[LJavaAlgorithm[_, _, _, Q, P]].
                  queryClass)
            } else {
              Extraction.extract(parse(queryString))(
                firstAlgorithm.querySerializer,
                firstAlgorithm.queryManifest)
            }
          val predictions = algorithms.zipWithIndex.map { case (a, ai) =>
            a.predictBase(models(ai), query)
          }
          val prediction = serving.serveBase(query, predictions)
          complete(compact(render(
            Extraction.decompose(prediction)(firstAlgorithm.querySerializer))))
        }
      }
    } ~
    path("reload") {
      get {
        complete {
          context.actorSelection("/user/master") ! ReloadServer()
          "Reloading..."
        }
      }
    } ~
    path("stop") {
      get {
        complete {
          context.system.scheduler.scheduleOnce(1.seconds) {
            context.actorSelection("/user/master") ! StopServer()
          }
          "Shutting down..."
        }
      }
    }
}