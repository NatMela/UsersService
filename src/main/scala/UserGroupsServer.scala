import controller.UsersController
import services.SwaggerDocService

import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.RouteConcatenation
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import com.google.inject.Guice
import com.typesafe.config.ConfigFactory
import config.DiModule


object UserServer extends App with RouteConcatenation {
  implicit def executor: ExecutionContextExecutor = system.dispatcher

  val inject = Guice.createInjector(new DiModule())

  val usersController = inject.getInstance(classOf[UsersController])

  implicit val system: ActorSystem = ActorSystem("UsersServer")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher

  val routes = cors()(usersController.userRoutes ~ SwaggerDocService.routes)

  val host = ConfigFactory.load().getString("serverConf.host")
  val port = ConfigFactory.load().getInt("serverConf.port")

  val serverBinding: Future[Http.ServerBinding] = Http().bindAndHandle(routes, host, port)


  serverBinding.onComplete {
    case Success(bound) =>
      println(s"Server online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")
    case Failure(e) =>
      Console.err.println(s"Server could not start!")
      e.printStackTrace()
      system.terminate()
  }

  Await.result(system.whenTerminated, Duration.Inf)
}
