package services

import java.sql.Date
import java.text.SimpleDateFormat
import java.time.LocalDate

import akka.stream.scaladsl.Source
import controller.{GroupsDTO, JsonSupport, UserWithGroupsDTO, UsersDTO, UsersFromPage, UsersOptionDTO}
import dao.{UserDAO, UserGroupsDAO, UsersAndGroupsRow, UsersRow}

import scala.concurrent.{ExecutionContext, Future}
import config._
import com.google.inject.Inject
import org.slf4j.LoggerFactory
import slick.jdbc.PostgresProfile.api._
import spray.json._
import spray.json.JsValue
import diffson.sprayJson._
import diffson.diff
import diffson.lcs.Patience
import diffson._
import diffson.jsonpatch.lcsdiff._
import javax.jms.{Session, TextMessage}
import org.apache.activemq.ActiveMQConnectionFactory
import slick.jdbc.{ResultSetConcurrency, ResultSetType}

import scala.util.{Failure, Success}

class UsersService @Inject()(userDAO: UserDAO, userGroupsDAO: UserGroupsDAO, dbConfig: Db) extends JsonSupport {

  lazy val log = LoggerFactory.getLogger(classOf[UsersService])
  implicit val ec = ExecutionContext.global

  implicit val lcs = new Patience[JsValue]

  val maxNumberOfGroups = 16
  val connFactory = new ActiveMQConnectionFactory()
  val conn = connFactory.createConnection()
  val session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)
  val destUsersServer = session.createQueue("users_server")
  val destUsersClient = session.createQueue("users_client")
  val destGroupsServer = session.createQueue("groups_server")
  val destGroupsClient = session.createQueue("groups_client")

  val usersServer = session.createProducer(destUsersServer)
  val usersClient = session.createConsumer(destUsersClient)
  val groupsServer = session.createConsumer(destGroupsServer)
  val groupsClient = session.createProducer(destGroupsClient)


  conn.start()

  def convertStrToInt(str: String): Option[Int] = {
    try {
      val result = str.toInt
      Option(result)
    } catch {
      case e: Throwable => None
    }
  }

  val listener = Future {
    while (true) {
      val message = groupsServer.receive()
      if (message.isInstanceOf[TextMessage]) {
        val s = message.asInstanceOf[TextMessage].getText
        s match {
          case msg if convertStrToInt(msg).isDefined =>
            val resultF = getUsersForGroup(msg.toInt)
            resultF.map(result => {
              groupsClient.send(session.createTextMessage(result.toString))
            })
          case _ =>
            Seq.empty[UsersDTO]
        }
        println("Received text:" + s)
      } else {
        println("Received unknown")
      }
    }
  }

  listener.onComplete {
    case Success(value) => println(s"Got the callback, value = $value")
    case Failure(e) => println(s"D'oh! The task failed: ${e.getMessage}")
  }

  def getUsersStream() = {
    val result = dbConfig.db().stream(userDAO.getUsers()).mapResult {
      usersRow =>
        UsersDTO(id = usersRow.id, firstName = usersRow.firstName, lastName = usersRow.lastName, createdAt = Some(usersRow.createdAt.toString), isActive = usersRow.isActive)
    }
    Source.fromPublisher(result)
  }

  def getUserById(userId: Int): Future[Option[UsersDTO]] = {
    dbConfig.db.run(userDAO.getUserById(userId)).map {
      userRows =>
        userRows.headOption match {
          case None => {
            log.warn("There is no user with id {}", userId)
            None
          }
          case Some(userRow) => {
            log.info("User with id {} was found", userId)
            Some(UsersDTO(id = userRow.id, firstName = userRow.firstName, lastName = userRow.lastName, createdAt = Some(userRow.createdAt.toString), isActive = userRow.isActive))
          }
        }
    }
  }

  def getUsersForGroup(groupId: Int): Future[Seq[UsersDTO]] = {
    val usersIdsForGroupF = dbConfig.db().run(userGroupsDAO.getAllUsersForGroup(groupId))
    usersIdsForGroupF.flatMap(usersId => dbConfig.db().run(userDAO.getUsersByIds(usersId)).map {
      usersRows =>
        usersRows.map(userRow => UsersDTO(id = userRow.id, firstName = userRow.firstName, lastName = userRow.lastName, createdAt = Some(userRow.createdAt.toString), isActive = userRow.isActive))
    })
  }

  def getDetailsForUser(userId: Int): Future[Option[UserWithGroupsDTO]] = {
    val userF: Future[Option[UsersDTO]] = dbConfig.db.run(userDAO.getUserById(userId)).map {
      userRows =>
        userRows.headOption match {
          case None =>
            log.info("There is no user with id {}", userId)
            None
          case Some(userRow) => Some(UsersDTO(id = userRow.id, firstName = userRow.firstName, lastName = userRow.lastName, createdAt = Some(userRow.createdAt.toString), isActive = userRow.isActive))
        }
    }

    val msg = session.createTextMessage(s"$userId")
    usersServer.send(msg)
    usersClient.setMessageListener(null)
    log.info("sent message")
    println("sent message")
    val answer = usersClient.receive(1000) //.asInstanceOf[TextMessage].getText
    log.info(s"receive answer ${answer}")

    val resultF = answer match {
      case message: TextMessage =>
        println(s"receive answer ${message.getText}")
        Future.successful(message.getText)

      case _ =>
        Future.successful("NotTextMessage")
    }

      val seqF = for {
        user <- userF
        result <- resultF
      } yield (user, result)

      seqF.map { res =>
      val (user, result) = res
        val groups = stringConvertToSetDTO(result,"GroupsDTO")
        user match {
          case None =>
            log.warn("Can't get details about user as there is no user with id {} ", userId)
            None
          case Some(user) => {
            log.info("Details for user with id {} were found", userId)
            Some(UserWithGroupsDTO(user, groups.toSeq))
          }
        }
      }
  }

  def getUsersFromPage(pageSize: Int, pageNumber: Int): Future[UsersFromPage] = {
    val result = userDAO.getUsersFromPage(pageNumber, pageSize)
    val numberOfAllUsersF = dbConfig.db.run(result._2)
    val usersOnPageF = dbConfig.db.run(result._1).map {
      userRows =>
        userRows.map(userRow =>
          UsersDTO(id = userRow.id, firstName = userRow.firstName, lastName = userRow.lastName, createdAt = Some(userRow.createdAt.toString), isActive = userRow.isActive))
    }
    val seqF = for {
      numberOfAllUsers <- numberOfAllUsersF
      usersOnPage <- usersOnPageF
    } yield (numberOfAllUsers, usersOnPage)
    seqF.map { result =>
      val (numberOfAllUsers, usersOnPage) = result
      UsersFromPage(usersOnPage, numberOfAllUsers, pageNumber, pageSize)
    }
  }

  def updateUserById(userId: Int, userRow: UsersDTO): Future[Option[UsersDTO]] = {
    val user = getUserById(userId)
    user.flatMap {
      case Some(user) => {
        log.info("User with id {} was found", userId)
        if (!userRow.isActive && user.isActive) {
          dbConfig.db.run(userGroupsDAO.deleteGroupsForUser(userId))
        }
        val date = userRow.createdAt.getOrElse(user.createdAt.get.toString)
        val rowToUpdate = UsersRow(id = Some(userId), createdAt = java.sql.Date.valueOf(date), firstName = userRow.firstName, lastName = userRow.lastName, isActive = userRow.isActive)
        dbConfig.db.run(userDAO.update(rowToUpdate)).flatMap(_ => getUserById(userId))
      }
      case None => {
        log.warn("Can't update info about user as there is no user with id {} ", userId)
        Future.successful(None)
      }
    }
  }

  def updateOneFieldOfUserById(userId: Int, userRow: UsersOptionDTO): Future[Option[UsersDTO]] = {
    val user = getUserById(userId)
    user.flatMap {
      case Some(user) => {
        val j1 = user.toJson
        log.debug(j1.toString())
        val j2 = userRow.toJson
        log.debug(j2.toString())

        log.info(j2.diff(j1).toString)
        val jd = diff(j1, j2)
        //        JsonPatch.diff(j1, j2)
        log.info("User with id {} was found", userId)
        /*
                if (!userRow.isActive && user.isActive) {
                  dbConfig.db.run(userGroupsDAO.deleteGroupsForUser(userId))
                }
               val date = userRow.createdAt.getOrElse(user.createdAt.get.toString)
               val rowToUpdate = UsersRow(id = Some(userId), createdAt = java.sql.Date.valueOf(date), firstName = userRow.firstName, lastName = userRow.lastName, isActive = userRow.isActive)        dbConfig.db.run(userDAO.update(rowToUpdate)).flatMap(_ => getUserById(userId))
         */
        Future.successful(None)
      }
      case None => {
        log.warn("Can't update info about user as there is no user with id {} ", userId)
        Future.successful(None)
      }
    }
  }
    def convertToDto(row: Seq[String]): GroupsDTO = {
      GroupsDTO(id = strToOptionInt(row.head.tail), title = row(1), createdAt = strToOptionDate(row(2)), description =  row(3).substring(0, row(3).length - 1))
    }

    def strToOptionInt(str: String): Option[Int] = {
      str match {
        case string if string.substring(0, 4) == "Some" =>
          Option(string.substring(5, string.length - 1).toInt)
        case _ => None
      }
    }

    def strToOptionDate(str: String): Option[String] = {
      str match {
        case string if string.substring(0, 4) == "Some" =>
          Option(string.substring(5, string.length - 1))
        case _ => None
      }
    }

    def stringConvertToSetDTO(inputString: String, dtoName: String): Seq[GroupsDTO] = {
      val setStrings: Seq[String] = inputString.substring(7, inputString.length - 1)
        .split(dtoName).toSeq.tail
      setStrings.map(row => row.split(",").toSeq)
        .map(row => convertToDto(row))
    }

    def insertUser(user: UsersDTO) = {
    val insertedUser = UsersRow(id = user.id, firstName = user.firstName, lastName = user.lastName, isActive = user.isActive, createdAt = java.sql.Date.valueOf(LocalDate.now))
    val idF = dbConfig.db.run(userDAO.insert(insertedUser))
    idF.flatMap { id =>
      dbConfig.db.run(userDAO.getUserById(id)).map {
        userRows =>
          userRows.headOption match {
            case None => {
              log.warn("User was not added ")
              None
            }
            case Some(userRow) => {
              log.info("User with id {} was created", userRow.id)
              Some(UsersDTO(id = userRow.id, firstName = userRow.firstName, lastName = userRow.lastName, createdAt = Some(userRow.createdAt.toString), isActive = userRow.isActive))
            }
          }
      }
    }
  }

  def isUserAlreadyInGroup(userId: Int, groupId: Int) = {
    val userGroupRowF = dbConfig.db.run(userGroupsDAO.getUserGroupRow(userId, groupId))
    userGroupRowF.map(userGroupRow =>
      if (userGroupRow.nonEmpty) true else false)
  }

  def couldWeAddGroupForUser(userId: Int) = {
    val groupsForUserF = dbConfig.db.run(userGroupsDAO.getAllGroupsForUser(userId))
    groupsForUserF.map(groupsForUser =>
      if (groupsForUser.size < maxNumberOfGroups) true else false)
  }

  def needToAddUserToGroup(userId: Int, groupId: Int) = {
    val seqF = for {
      isUserInGroup <- isUserAlreadyInGroup(userId, groupId)
      couldWeAddGroup <- couldWeAddGroupForUser(userId)
    } yield (isUserInGroup, couldWeAddGroup)
    seqF.map { result =>
      val (isUserInGroup, couldWeAddGroup) = result
      if (!isUserInGroup && couldWeAddGroup)
        true
      else
        false
    }
  }

  def addUserToGroup(userId: Int, groupId: Int): Future[String] = {
    val userF = getUserById(userId)
    // dbConfig.db.run(groupsDAO.getGroupById(groupId)).flatMap(groupRows =>
    // groupRows.headOption match {
    Option(groupId) match {
      case Some(_) => {
        userF.flatMap {
          case Some(user) => {
            if (user.isActive) {
              needToAddUserToGroup(userId, groupId).flatMap { needToAdd =>
                if (needToAdd) {
                  log.info("Add user with id {} to group with id {}", userId, groupId)
                  val rowToInsert = UsersAndGroupsRow(None, userId, groupId)
                  dbConfig.db.run(userGroupsDAO.insert(rowToInsert))
                  Future.successful(s"")
                } else {
                  log.warn("Don't add user to group as user with id {} is already in group with id {} or user is included for {} groups", userId, groupId, maxNumberOfGroups)
                  Future.successful(s"Don't add user to group as user with id $userId is already in group with id $groupId or user is already included in $maxNumberOfGroups groups")
                }
              }
            } else {
              log.warn("Don't add user to group as user with id {} is is nonActive", userId)
              Future.successful(s"Don't add user to group as user with id $userId is nonActive")
            }
          }
          case None => {
            log.warn("Don't add user to group as user with id {} is not exist", userId)
            Future.successful(s"Don't add user to group as user with id $userId is not exist")
          }
        }
      }
      case None => {
        log.warn("Don't add user to group as group with id {} is not exist", groupId)
        Future.successful(s"Don't add user to group as group with id $groupId is not exist")
      }
    } //)
  }

  def deleteUser(userId: Int): Future[Unit] = {
    getUserById(userId).map {
      case Some(_) => val query = DBIO.seq(userDAO.delete(userId), userGroupsDAO.deleteGroupsForUser(userId)).transactionally
        dbConfig.db.run(query)
        val message = s"User with id $userId is deleted"
        log.info(message)
      case None => val message = s"User with id $userId is not found"
        log.info(message)
    }
  }


  def setUserAsActive(userId: Int) = {
    val userF = getUserById(userId)
    userF.flatMap {
      case Some(user) => {
        log.info("Set user with id {} as active", userId)
        val rowToUpdate = UsersDTO(id = Some(userId), firstName = user.firstName, lastName = user.lastName, createdAt = user.createdAt, isActive = true)
        updateUserById(userId, rowToUpdate)
      }
      case None => {
        log.warn("Can't make user active because can't find user with id {} ", userId)
        Future.successful(None)
      }
    }
  }

  def setUserAsNonActive(userId: Int) = {
    val userF = getUserById(userId)
    userF.flatMap {
      case Some(user) => {
        log.info("Set user with id {} as nonActive", userId)
        val rowToUpdate = UsersDTO(id = Some(userId), firstName = user.firstName, lastName = user.lastName, createdAt = user.createdAt, isActive = false)
        dbConfig.db.run(userGroupsDAO.deleteGroupsForUser(userId))
        updateUserById(userId, rowToUpdate)
      }
      case None => {
        log.warn("Can't make user nonActive as can't find user with id {} ", userId)
        Future.successful(None)
      }
    }
  }

  def deleteUserFromGroup(userId: Int, groupId: Int): Future[Unit] = {
    dbConfig.db().run(userGroupsDAO.deleteRowForParticularUserAndGroup(userId, groupId))
    val message = s"User with id $userId is deleted from group with $groupId"
    log.info(message)
    Future.successful()
  }
}
