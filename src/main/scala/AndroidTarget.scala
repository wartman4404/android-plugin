package sbtandroid

import sbt._
import Keys._
import AndroidHelpers._

import java.io.InputStream

import scala.language.postfixOps

/**
 * Android target base trait
 */
trait AndroidTarget {
  /**
   * Target-specific options send to ADB
   */
  val options: Seq[String]

  def isPresent(path: String): Either[String, Unit]

  /**
   * Runs ADB commands for the given adb executable path.
   */
  def apply(adbPath: File, extra: String*) = {
    // Full command line
    val command = Seq(adbPath.absolutePath) ++ options ++ extra

    // Output buffer
    val streamer = new FIFOStream[String]()
    val io = sys.process.BasicIO.processFully(streamer enqueue _)
    val append = (is: InputStream) => { io(is); streamer close(); () }
    lazy val stream = streamer.toStream

    // Run the command and grab the exit value
    val process = command.run(new ProcessIO(
      in => (),
      append,
      append,
      inheritedInput => false
    ))

    val firstLine = stream.headOption.getOrElse("")
    if (((firstLine) contains "error: device not found") || ((firstLine) contains "error: more than one")) {
      (-1, firstLine)
    } else {
      (process.exitValue, stream.mkString)
    }
  }

  /**
   * Returns a task that simply runs the specified ADB command, and sends the
   * output to the SBT logs.
   */
  def run(adbPath: File, s: TaskStreams, extra: String*) = {
    // Display the command in the debug logs
    s.log.debug((Seq(adbPath.absolutePath) ++ options ++ extra).mkString(" "))

    s.log.debug("looking for device")
    this.isPresent(adbPath.toString) match {
      case Left(message) => sys.error("Unable to resolve device %s: %s\nPlease select another target.".format(this, message))
      case Right(_) => { }
    }

    // Run the command
    val (exit, output) = this(adbPath, extra: _*)

    // Display the error and fail on ADB failure
    if (exit != 0 || output.contains("Failure")) {
      s.log.error(output)
      sys.error("Error executing ADB")

    // If the command succeeded, log the output to the debug stream
    } else s.log.debug(output)

    // Return the output
    output
  }

  /**
   * Starts the app on the target
   */
  def startApp(
    adbPath: File,
    s: TaskStreams,
    manifestSchema: String,
    manifestPackage: String,
    manifestPath: Seq[java.io.File]) = {

    // Target activity (defined in the manifest)
    val activity = launcherActivity(
      manifestSchema,
      manifestPath.head,
      manifestPackage)

    // Full intent target
    val intentTarget = manifestPackage + "/" + activity

    // Run the command
    run(adbPath, s,
      "shell", "am", "start",
      "-a", "android.intent.action.MAIN",
      "-n", intentTarget
    )
  }

  /**
   * Runs instrumentation tests on an app
   */
  def testApp(
    adbPath: File,
    manifestPackage: String,
    testRunner: String,
    testName: Option[String] = None) = {

    // Full intent target
    val intentTarget = manifestPackage + "/" + testRunner

    // Run the command
    testName match {
      case Some(test) =>
        this(adbPath,
          "shell", "am", "instrument",
          "-r",
          "-e", "class", test,
          "-w", intentTarget)

      case None =>
        this(adbPath,
          "shell", "am", "instrument",
          "-r",
          "-w", intentTarget)
    }
  }

  /**
   * Installs or uninstalls a package on the target
   */
   def installPackage(adbPath: File, streams: TaskStreams, apkPath: File) =
     run(adbPath, streams, "install", "-r", apkPath.absolutePath)
   def uninstallPackage(adbPath: File, streams: TaskStreams, packageName: String) =
     run(adbPath, streams, "uninstall", packageName)
}

/**
 * Some common Android target definitions
 */
object AndroidDefaultTargets {

  /**
   * Selects a connected Android target
   */
  case object Auto extends AndroidTarget {
    val options = Seq.empty
    override def isPresent(path: String) = {
      AndroidDdm.listDevices(path).size match {
        case 0 => Left("No devices or emulators connected (need exactly 1)")
        case 1 => Right(Unit)
        case _ => Left("Too many devices and emulators connected (need exactly 1)")
      }
    }
  }

  /**
   * Selects a connected Android device
   */
  case object Device extends AndroidTarget {
    val options = Seq("-d")
    override def isPresent(path: String) = {
      AndroidDdm.listDevices(path).filterNot(_.isEmulator).size match {
        case 0 => Left("No physical devices connected (need exactly 1)")
        case 1 => Right(Unit)
        case _ => Left("Too many physical devices connected (need exactly 1)")
      }
    }
  }

  /**
   * Selects a connected Android emulator
   */
  case object Emulator extends AndroidTarget {
    val options = Seq("-e")
    override def isPresent(path: String) = {
      AndroidDdm.listDevices(path).filter(_.isEmulator).size match {
        case 0 => Left("No emulators connected (need exactly 1)")
        case 1 => Right(Unit)
        case _ => Left("Too many emulators connected (need exactly 1)")
      }
    }
  }

  /**
   * Selects any Android device or emulator matching the given UID
   */
  case class UID(val uid: String) extends AndroidTarget {
    val options = Seq("-s", uid)
    override def isPresent(path: String) = {
      if (AndroidDdm.listDeviceSerials(path).contains(uid)) Right(Unit)
      else Left("No device identified as %s connected".format(uid))
    }
  }
}
