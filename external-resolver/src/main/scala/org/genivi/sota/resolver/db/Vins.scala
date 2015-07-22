package org.genivi.sota.core.db

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import slick.driver.MySQLDriver.api._
import org.genivi.sota.resolver.Vin
import org.genivi.sota.resolver.db.DatabaseConfig

object Vins extends DatabaseConfig {

  class VinTable(tag: Tag) extends Table[Vin](tag, "Vin") {

    def vin = column[String]("vin", O.PrimaryKey)

    def * = (vin) <> (Vin.apply, Vin.unapply)
  }

  val vins = TableQuery[VinTable]

  def list: Future[Seq[Vin]] = db.run(vins.result)

  def create(vin: Vin)(implicit ec: ExecutionContext): Future[Vin] = db.run(vins += vin).map(_ => vin)
}
