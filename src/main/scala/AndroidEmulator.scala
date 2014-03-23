package sbtandroid

import sbt._
import Keys._

import AndroidPlugin._
import AndroidHelpers.isWindows

import complete.DefaultParsers._

import scala.language.postfixOps

object AndroidEmulator {
  private def emulatorStartTask = Def.inputTask {
    val avd = installedAvds.parsed
    "%s/emulator -avd %s".format(toolsPath.value, avd).run
    ()
  }

  private def listDevicesTask: Def.Initialize[Task[Unit]] = (dbPath) map {
    _ +" devices" !
  }

  private def killAdbTask: Def.Initialize[Task[Unit]] = (dbPath) map {
    _ +" kill-server" !
  }

  private def emulatorStopTask = (dbPath, streams) map { (dbPath, s) =>
    s.log.info("Stopping emulators")
    val serial = "%s -e get-serialno".format(dbPath).!!
    "%s -s %s emu kill".format(dbPath, serial).!
    ()
  }

  def installedAvds = Def.setting { (s: State) =>
    val avds = ((Path.userHome / ".android" / "avd" * "*.ini") +++
      (if (isWindows) (sdkPath.value / ".android" / "avd" * "*.ini")
       else PathFinder.empty)).get
    Space ~> avds.map(f => token(f.base))
                 .reduceLeftOption(_ | _).getOrElse(token("none"))
  }

  lazy val baseSettings: Seq[Setting[_]] = (Seq(
    listDevices <<= listDevicesTask,
    killAdb <<= killAdbTask,
    emulatorStart <<= emulatorStartTask,
    emulatorStop <<= emulatorStopTask
  ))

  lazy val aggregateSettings: Seq[Setting[_]] = Seq(
    listDevices,
    emulatorStart,
    emulatorStop
  ) map { aggregate in _ := false }

  lazy val settings: Seq[Setting[_]] = baseSettings ++ aggregateSettings
}
