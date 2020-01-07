package dao

import slick.jdbc.PostgresProfile.api._
import java.sql.Date

import javax.inject.{Inject, Singleton}
import slick.dbio.Effect
import slick.sql.FixedSqlStreamingAction

case class UsersRow(id: Option[Int], firstName: String, lastName: String, createdAt: Date, isActive: Boolean)


class UsersTable(tag: Tag) extends Table[UsersRow](tag, "users") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def firstName = column[String]("first_name")

  def lastName = column[String]("last_name")

  def createdAt = column[Date]("created_at")

  def isActive = column[Boolean]("is_active")

  def * = (id.?, firstName, lastName, createdAt, isActive) <> (UsersRow.tupled, UsersRow.unapply)

}

@Singleton
class UserDAO @Inject()() {
  val allUsers = TableQuery[UsersTable]

  def getUsers() = {
    val neededUsers = allUsers
    neededUsers.result
  }

  def getUserById(userId: Int) = {
    val user = allUsers.filter(_.id === userId)
    user.result
  }

  def getUsersByIds(userIds: Seq[Int]) = {
    allUsers.filter(_.id inSet userIds).result
  }

  def getUsersFromPage(pageNumber: Int, pageSize: Int) = {
    val startNumberOfNeededUsers = (pageNumber - 1) * pageSize
    val skipPages = allUsers.drop(startNumberOfNeededUsers)
    val usersFromPage = skipPages.take(pageSize)
    val numberOfAllUsers =allUsers.size
    (usersFromPage.result, numberOfAllUsers.result)
  }

  def update(user: UsersRow) = {
    allUsers.filter(_.id === user.id).update(user)
  }

  def delete(id: Int) = {
    allUsers.filter(_.id === id).delete
  }

  def insert(user: UsersRow) = {
    (allUsers returning allUsers.map(_.id)) += user
  }

}


