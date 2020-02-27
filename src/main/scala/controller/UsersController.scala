package controller

import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._
import services.UsersService
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.util.ByteString
import com.google.inject.{Guice, Inject}
import config.{Db, DiModule}
import dao.{UserDAO, UserGroupsDAO}
import io.swagger.annotations._
import javax.ws.rs.Path
import org.slf4j.LoggerFactory
import org.apache.activemq.ActiveMQConnectionFactory
import javax.jms.Session
import org.apache.log4j.BasicConfigurator

import scala.concurrent.ExecutionContext

case class UsersDTO(id: Option[Int], firstName: String, lastName: String, createdAt: Option[String], isActive: Boolean)

case class UsersOptionDTO(id: Option[Int], firstName: Option[String], lastName: Option[String], createdAt: Option[String], isActive: Option[Boolean])

case class GroupsDTO(id: Option[Int], title: String, createdAt: Option[String], description: String)

case class GroupsOptionDTO(id: Option[Int], title: Option[String], createdAt: Option[String], description: Option[String])

case class UsersFromPage(users: Seq[UsersDTO], totalAmount: Int, pageNumber: Int, pageSize: Int)

case class GroupsFromPage(groups: Seq[GroupsDTO], totalAmount: Int, pageNumber: Int, pageSize: Int)

case class UserGroupsDTO(id: Option[Int], userId: Int, groupId: Int)

case class UserWithGroupsDTO(userInfo: UsersDTO, groups: Seq[GroupsDTO])

case class GroupWithUsersDTO(groupInfo: GroupsDTO, users: Seq[UsersDTO])

case class LinksDTO(ref: String, link: String)

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

@Path("/users")
@Api(value = "Users Controller")
class UsersController @Inject()(userDAO: UserDAO, userGroupsDAO: UserGroupsDAO, dbConfig: Db) extends JsonSupport {

  lazy val logger = LoggerFactory.getLogger(classOf[UsersController])
  BasicConfigurator.configure()

  val newline = ByteString("\n")
  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
    EntityStreamingSupport.json()


  val defaultNumberOfUsersOnPage = 20
  val defaultPageNumberForUsers = 1
  val maxPageSizeForUsers = 100

  val injector = Guice.createInjector(new DiModule())
  val service = injector.getInstance(classOf[UsersService])





  @ApiOperation(value = "Get all users", httpMethod = "GET", response = classOf[UsersDTO])
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 200, message = "Step performed successfully")
  ))
  @Path("/all")
  def getAllUsers: Route =
    pathEnd {
      get {
        complete(service.getUsersStream())
      }
    }

  @ApiOperation(value = "Get users from particular page", httpMethod = "GET", response = classOf[UsersDTO])
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "pageNumber", required = false, dataType = "number", paramType = "query", value = "page number (starts from 1)"),
    new ApiImplicitParam(name = "pageSize", required = false, dataType = "number", paramType = "query", value = "number of items shown per page")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 200, message = "Step performed successfully")
  ))
  @Path("/")
  def getUsersFromPage: Route =
    pathEnd {
      get {
        parameterMultiMap { params =>
          val pageSize = params.get("pageSize").flatMap(_.headOption).map(_.toInt).getOrElse(0)
          val pageNumber = params.get("pageNumber").flatMap(_.headOption).map(_.toInt).getOrElse(0)
          if ((pageNumber > 0) && (pageSize > 0)) {
            if (pageSize > maxPageSizeForUsers) {
              complete(service.getUsersFromPage(maxPageSizeForUsers, pageNumber))
            } else
              complete(service.getUsersFromPage(pageSize, pageNumber))
          } else {
            complete(service.getUsersFromPage(defaultNumberOfUsersOnPage, defaultPageNumberForUsers))
          }
        }
      }
    }


  @ApiOperation(value = "Get user by Id", httpMethod = "GET", response = classOf[UsersDTO])
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "id", required = true, dataType = "integer", paramType = "path", value = "User Id")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 200, message = "Step performed successfully"),
    new ApiResponse(code = 204, message = "No user with such id was found")
  ))
  @Path("/{id}")
  def getUserById(@ApiParam(hidden = true) id: Int): Route =
    pathEnd {
      get {
        onComplete(service.getUserById(id)) {
          case util.Success(Some(response)) => complete(StatusCodes.OK, response)
          case util.Success(None) => complete(StatusCodes.NoContent)
          case util.Failure(ex) => complete(StatusCodes.BadRequest, s"An error occurred: ${ex.getMessage}")
        }
      }
    }

  @ApiOperation(value = "Update user by Id", httpMethod = "PUT", response = classOf[UsersDTO])
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "id", required = true, dataType = "integer", paramType = "path", value = "User Id"),
    new ApiImplicitParam(name = "userRow", required = true, dataType = "controller.UsersDTO", paramType = "body", value = "Row to update users information")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 200, message = "Step performed successfully"),
    new ApiResponse(code = 204, message = "No user with such id was found")
  ))
  @Path("/{id}")
  def updateUserById(@ApiParam(hidden = true) id: Int): Route =
    pathEnd {
      put {
        entity(as[UsersDTO]) { userRow =>
          onComplete(service.updateUserById(id, userRow)) {
            case util.Success(Some(response)) => complete(StatusCodes.OK, response)
            case util.Success(None) => complete(StatusCodes.NoContent)
            case util.Failure(ex) => complete(StatusCodes.BadRequest, s"An error occurred: ${ex.getMessage}")
          }
        }
      }
    }

  @ApiOperation(value = "Update one field of user by Id", httpMethod = "PATCH", response = classOf[UsersDTO])
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "id", required = true, dataType = "integer", paramType = "path", value = "User Id"),
    new ApiImplicitParam(name = "userRow", required = true, dataType = "controller.UsersOptionDTO", paramType = "body", value = "Row to update users information")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 200, message = "Step performed successfully"),
    new ApiResponse(code = 204, message = "No user with such id was found")
  ))
  @Path("/{id}")
  def updateOneFieldOfUserById(@ApiParam(hidden = true) id: Int): Route =
    pathEnd {
      patch {
        entity(as[UsersOptionDTO]) { userRow =>
          onComplete(service.updateOneFieldOfUserById(id, userRow)) {
            case util.Success(Some(response)) => complete(StatusCodes.OK, response)
            case util.Success(None) => complete(StatusCodes.NoContent)
            case util.Failure(ex) => complete(StatusCodes.BadRequest, s"An error occurred: ${ex.getMessage}")
          }
        }
      }
    }

  @ApiOperation(value = "Set user as active", httpMethod = "PUT", response = classOf[UsersDTO])
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "id", required = true, dataType = "integer", paramType = "path", value = "User Id")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 200, message = "Step performed successfully"),
    new ApiResponse(code = 204, message = "No user with such id was found")
  ))
  @Path("/{id}/active")
  def setUserAsActive(@ApiParam(hidden = true) id: Int): Route =
    pathEnd {
      put {
        onComplete(service.setUserAsActive(id)) {
          case util.Success(Some(response)) => complete(StatusCodes.OK, response)
          case util.Success(None) => complete(StatusCodes.NoContent)
          case util.Failure(ex) => complete(StatusCodes.BadRequest, s"An error occurred: ${ex.getMessage}")
        }
      }
    }

  @ApiOperation(value = "Set user as non active", httpMethod = "PUT", response = classOf[UsersDTO])
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "id", required = true, dataType = "integer", paramType = "path", value = "User Id")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 200, message = "Step performed successfully"),
    new ApiResponse(code = 204, message = "No user with such id was found")
  ))
  @Path("/{id}/nonActive")
  def setUserAsNonActive(@ApiParam(hidden = true) id: Int): Route =
    pathEnd {
      put {
        onComplete(service.setUserAsNonActive(id)) {
          case util.Success(Some(response)) => complete(StatusCodes.OK, response)
          case util.Success(None) => complete(StatusCodes.NoContent)
          case util.Failure(ex) => complete(StatusCodes.BadRequest, s"An error occurred: ${ex.getMessage}")
        }
      }
    }

  @ApiOperation(value = "Delete user by Id", httpMethod = "DELETE", response = classOf[String])
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "id", required = true, dataType = "integer", paramType = "path", value = "User Id")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 200, message = "Step performed successfully")
  ))
  @Path("/{id}")
  def deleteUser(@ApiParam(hidden = true) id: Int): Route =
    pathEnd {
      delete {
        onComplete(service.deleteUser(id)) {
          case util.Success(_) => complete(StatusCodes.OK)
          case util.Failure(ex) => complete(StatusCodes.NotFound, s"An error occurred: ${ex.getMessage}")
        }
      }
    }

  @ApiOperation(value = "Delete user from group", httpMethod = "DELETE", response = classOf[String])
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "userId", required = true, dataType = "integer", paramType = "path", value = "User Id"),
    new ApiImplicitParam(name = "groupId", required = true, dataType = "integer", paramType = "path", value = "Group Id")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 200, message = "Step performed successfully")
  ))
  @Path("/{userId}/groups/{groupId}")
  def deleteUserFromGroup(@ApiParam(hidden = true) userId: Int, @ApiParam(hidden = true) groupId: Int): Route =
    pathEnd {
      delete {
        onComplete(service.deleteUserFromGroup(userId, groupId)) {
          case util.Success(_) => complete(StatusCodes.OK)
          case util.Failure(ex) => complete(StatusCodes.NotFound, s"An error occurred: ${ex.getMessage}")
        }
      }
    }


  @ApiOperation(value = "Add user to group", httpMethod = "POST", response = classOf[String])
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "userId", required = true, dataType = "integer", paramType = "path", value = "User Id"),
    new ApiImplicitParam(name = "groupId", required = true, dataType = "integer", paramType = "path", value = "Group Id")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 409, message = "Bad request"),
    new ApiResponse(code = 200, message = "Step performed successfully")
  ))
  @Path("/{userId}/groups/{groupId}")
  def addUserToGroup(@ApiParam(hidden = true) userId: Int, @ApiParam(hidden = true) groupId: Int): Route =
    pathEnd {
      post {
        onComplete(service.addUserToGroup(userId, groupId)) {
          case util.Success(response) => {
            response match {
              case "" => complete(StatusCodes.OK)
              case _ => complete(StatusCodes.BadRequest, response)
            }
          }
          case util.Failure(ex) => complete(StatusCodes.BadRequest, s"An error occurred: ${ex.getMessage}")
        }
      }
    }

  @ApiOperation(value = "Insert user", httpMethod = "POST", response = classOf[UsersDTO])
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "userRow", required = true, dataType = "controller.UsersDTO", paramType = "body", value = "Row to insert")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 201, message = "Step performed successfully")
  ))
  @Path("/")
  def insertUser(): Route =
    pathEnd {
      post {
        entity(as[UsersDTO]) { userRow =>
          onComplete(service.insertUser(userRow)) {
            case util.Success(Some(response)) => complete(StatusCodes.Created, response)
            case util.Success(None) => complete(StatusCodes.BadRequest, s"User was not inserted")
            case util.Failure(ex) => complete(StatusCodes.BadRequest, s"An error occurred: ${ex.getMessage}")
          }
        }
      }
    }


  @ApiOperation(value = "Get information about groups for user with given id ", httpMethod = "GET", response = classOf[UserWithGroupsDTO])
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "id", required = true, dataType = "integer", paramType = "path", value = "User Id")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 400, message = "Bad request passed to the endpoint"),
    new ApiResponse(code = 200, message = "Step performed successfully"),
    new ApiResponse(code = 204, message = "No user with such id was found")
  ))
  @Path("/{id}/details")
  def getUserDetails(@ApiParam(hidden = true) id: Int): Route =
    pathEnd {
      get {
        onComplete(service.getDetailsForUser(id)) {
          case util.Success(Some(response)) => complete(StatusCodes.OK, response)
          case util.Success(None) => complete(StatusCodes.NoContent)
          case util.Failure(ex) => complete(StatusCodes.BadRequest, s"An error occurred: ${ex.getMessage}")
        }
      }
    }

  lazy val userRoutes: Route = {
    pathPrefix("users") {
      getUsersFromPage ~
        insertUser() ~
        pathPrefix("all") {
          getAllUsers
        } ~
        pathPrefix(IntNumber) { userId =>
          getUserById(userId) ~
            updateUserById(userId) ~
            updateOneFieldOfUserById(userId) ~
            deleteUser(userId) ~
            pathPrefix("groups") {
              pathPrefix(IntNumber) { groupId =>
                deleteUserFromGroup(userId, groupId) ~
                  addUserToGroup(userId, groupId)
              }
            } ~
            pathPrefix("details") {
              getUserDetails(userId)
            } ~
            pathPrefix("active") {
              setUserAsActive(userId)
            } ~
            pathPrefix("nonActive") {
              setUserAsNonActive(userId)
            }
        }
    }
  }
}


