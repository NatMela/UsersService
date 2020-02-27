import UserServer.inject
import controller.{GroupWithUsersDTO, GroupsDTO, GroupsFromPage, GroupsOptionDTO, JsonSupport, LinksDTO, UserGroupsDTO, UserWithGroupsDTO, UsersController, UsersDTO, UsersFromPage, UsersOptionDTO}
import services.{SwaggerDocService, UsersService}

import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.RouteConcatenation
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import com.google.inject.Guice
import com.typesafe.config.ConfigFactory
import config.DiModule
import javax.jms.{Message, MessageListener, Session, TextMessage}
import org.apache.activemq.ActiveMQConnectionFactory
import spray.json.DefaultJsonProtocol

import scala.concurrent.duration._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val userFormat = jsonFormat5(UsersDTO)
  implicit val userOptionFormat = jsonFormat5(UsersOptionDTO)
  implicit val groupsFormat = jsonFormat4(GroupsDTO)
  implicit val groupsOptionFormat = jsonFormat4(GroupsOptionDTO)
  implicit val usersPageFormat = jsonFormat4(UsersFromPage)
  implicit val groupsPageFormat = jsonFormat4(GroupsFromPage)
  implicit val userGroupsFormat = jsonFormat2(UserWithGroupsDTO)
  implicit val groupsUserFormat = jsonFormat2(GroupWithUsersDTO)
  implicit val usersGroupsFormat = jsonFormat3(UserGroupsDTO)
  implicit val linksFormat = jsonFormat2(LinksDTO)
}
/*
class GroupsListener(u: UserServer.type) extends MessageListener with JsonSupport{
  val injector = Guice.createInjector(new DiModule())
  val service = injector.getInstance(classOf[UsersService])

  override def onMessage(message: Message): Unit = {
    if (message.isInstanceOf[TextMessage]){
      val s = message.asInstanceOf[TextMessage].getText
      s match {
        case msg if convertStrToInt(msg).isDefined =>
          val result = Await.result(service.getUsersForGroup(msg.toInt), Duration(1000, MILLISECONDS))
            u.groupsClient.send(u.session.createTextMessage(result.toString))
        case _ =>
          Seq.empty[UsersDTO]
      }
      println("Received text:" + s)
    } else {
      println("Received unknown")
    }
  }

  def convertStrToInt(str: String): Option[Int] = {
    try{
      val result = str.toInt
      Option(result)
    }catch{
      case e: Throwable => None
    }
  }
}
*/
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
