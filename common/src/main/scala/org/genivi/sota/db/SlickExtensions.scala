/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.db

import java.sql.{BatchUpdateException, SQLIntegrityConstraintViolationException, Timestamp}
import java.util.UUID

import akka.http.scaladsl.model.Uri
import java.time.Instant

import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Uuid
import org.genivi.sota.data.{Namespace, PaginatedResult}
import org.genivi.sota.http.Errors
import slick.ast.{Node, TypedType}
import slick.jdbc.MySQLProfile.api._
import slick.lifted.Rep

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
  * Explain to the database layer, Slick, how to map Uri and UUIDs into
  * types that the database knows about (strings).
  *
  * @see {@link http://slick.typesafe.com/docs/}
  */

trait SlickExtensions {
  val defaultLimit = 50
  val maxLimit = 1000

  implicit class DbioPaginateExtensions[E, U, A](action: Query[E, U, Seq]) {

    def paginate(offset: Long, limit: Long): Query[E, U, Seq] =
      action.drop(offset).take(limit.min(maxLimit))

    def defaultPaginate(offset: Option[Long], limit: Option[Long]): Query[E, U, Seq] =
      action.paginate(offset.getOrElse(0), limit.getOrElse(defaultLimit))

    def paginateAndSort[T <% slick.lifted.Ordered](fn: E => T, offset: Long, limit: Long): Query[E, U, Seq] =
      action.sortBy(fn).paginate(offset, limit)

    def defaultPaginateAndSort[T <% slick.lifted.Ordered]
        (fn: E => T, offset: Option[Long], limit: Option[Long]): Query[E, U, Seq] =
      action.sortBy(fn).defaultPaginate(offset, limit)

    def paginatedResult(offset: Option[Long], limit: Option[Long])
                       (implicit ec: ExecutionContext):  DBIO[PaginatedResult[U]] = {
      val o = offset.getOrElse(0L)
      val l = limit.getOrElse[Long](defaultLimit).min(maxLimit)

      action.length.result.zip(action.paginate(o, l).result).map {
        case (total, values) => PaginatedResult(total=total, limit=l, offset=o, values=values)
      }
    }

    def paginatedResult[T <% slick.lifted.Ordered](fn: E => T, offset: Option[Long], limit: Option[Long])
                                                  (implicit ec: ExecutionContext):  DBIO[PaginatedResult[U]] =
      action.sortBy(fn).paginatedResult(offset, limit)
  }
}

object SlickExtensions {
  implicit val UriColumnType = MappedColumnType.base[Uri, String](_.toString(), Uri.apply)

  implicit val uuidColumnType = MappedColumnType.base[UUID, String]( _.toString(), UUID.fromString )

  implicit val namespaceColumnType = MappedColumnType.base[Namespace, String](_.get, Namespace.apply)
  /**
    * Define how to store a [[java.time.Instant]] in the SQL database.
    */
  implicit val javaInstantMapping = {
    MappedColumnType.base[Instant, Timestamp](
      dt => Timestamp.from(dt),
      ts => ts.toInstant)
  }

  final class MappedExtensionMethods(val n: Node) extends AnyVal {

    def mappedTo[U: TypedType] = Rep.forNode[U](n)

  }

  import scala.language.implicitConversions

  implicit def mappedColumnExtensions(c: Rep[_]) : MappedExtensionMethods = new MappedExtensionMethods(c.toNode)

  implicit def uuidToJava(refined: Refined[String, Uuid]): Rep[UUID] =
    UUID.fromString(refined.value).bind

  implicit class DbioActionExtensions[T](action: DBIO[T]) {
    def handleIntegrityErrors(error: Throwable)(implicit ec: ExecutionContext): DBIO[T] = {
      action.asTry.flatMap {
        case Success(i) =>
          DBIO.successful(i)
        case Failure(e: SQLIntegrityConstraintViolationException) =>
          DBIO.failed(error)
        case Failure(e: BatchUpdateException) if e.getCause.isInstanceOf[SQLIntegrityConstraintViolationException] =>
          DBIO.failed(error)
        case Failure(e) =>
          DBIO.failed(e)
      }
    }
  }

  implicit class DbioUpdateActionExtensions(action: DBIO[Int]) {
    def handleSingleUpdateError(result: Throwable)(implicit ec: ExecutionContext): DBIO[Unit] = {
      action.flatMap {
        case c if c == 1 =>
          DBIO.successful(())
        case c if c == 0 =>
          DBIO.failed(result)
        case _ =>
          DBIO.failed(Errors.TooManyElements)
      }
    }
  }

  implicit class DBIOOps[T](io: DBIO[Option[T]]) {

    def failIfNone(t: Throwable)
                  (implicit ec: ExecutionContext): DBIO[T] =
      io.flatMap(_.fold[DBIO[T]](DBIO.failed(t))(DBIO.successful))
  }

  implicit class DBIOSeqOps[+T](io: DBIO[Seq[T]]) {
    def failIfNotSingle(t: Throwable)
                       (implicit ec: ExecutionContext): DBIO[T] = {
      val dbio = io.flatMap { result =>
        if(result.size > 1)
          DBIO.failed(Errors.TooManyElements)
        else
          DBIO.successful(result.headOption)
      }

      DBIOOps(dbio).failIfNone(t)
    }
  }
}
