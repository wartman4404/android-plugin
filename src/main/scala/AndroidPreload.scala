package sbtandroid

import sbt._
import Keys._
import AndroidPlugin._
import AndroidHelpers._

import scala.xml._
import scala.xml.transform._

object AndroidPreload {

  case class Library(val localJar: File, val name: String, val version: String = "unknown") {
    val fullName = name + "-" + version
    val deviceJarPath = "/system/framework/%s.jar".format(fullName)
    val devicePermissionPath = "/system/etc/permissions/%s.xml".format(fullName)

    def usesTag =
      <uses-library
        android:name={ fullName }
        android:required="true" />

    def permissionTag =
      <permissions>
        <library
        name={ fullName }
        file={ deviceJarPath } />
      </permissions>
  }

  private def deviceDesignation(implicit emulator: Boolean) =
    if (emulator) "emulator" else "device"

  /**
   * Rewrite rule to add the uses-library tag to the Android manifest if the
   * preloaded library is needed.
   */
  case class UsesLibraryRule(use: Boolean, libraries: Seq[Library]) extends RewriteRule {
    val namespacePrefix = "http://schemas.android.com/apk/res/android"

    override def transform(n: scala.xml.Node): Seq[scala.xml.Node] = n match {
      case Elem(namespace, "application", attribs,
                scope, children @ _*) if (use) => {

        // Create a new uses-library tag
        val tags = libraries map { _.usesTag }

        // Update the element
        Elem(namespace, "application", attribs, scope, true, children ++ tags: _*)
      }

      case other => other
    }
  }

  /****************
   * State checks *
   ****************/

  private def checkFileExists (db: File, s: TaskStreams, filename: String)(implicit androidTarget: AndroidTarget) = {

    // Run the `ls` command on the device/emulator
    val flist = androidTarget.run(db, s, "shell", "ls", filename, "2>/dev/null")

    // Check if we found the file
    val found = flist.contains(filename)

    // Inform the user
    s.log.debug ("File " + filename +
      (if (found) " found on " else " does not exist on ") + androidTarget)

    // Return `true` if the file has been found
    found
  }

  private def checkPreloadedLibraryVersion (db: File, s: TaskStreams, library: Library)(implicit androidTarget: AndroidTarget) = {
    import scala.xml._

    // Retrieve the contents of the permission file
    val permissions = androidTarget.run(db, s, "shell", "cat " + library.devicePermissionPath)

    // Parse the library file
    val preloadedFile = (
      try { Some(XML.loadString(permissions) \\ "permissions" \\ "library" \\ "@file") }
      catch { case _ : Throwable => None }

    // Convert the XML node to a String
    ).map(_.text)

    // Check if this is the right library version
    .filter(_ == library.deviceJarPath)

    // Check if the library is present
    .filter(checkFileExists(db, s, _))

    // Inform the user
    preloadedFile match {
      case Some(f) =>
        s.log.info("Library " + library.fullName + " is already preloaded")
      case None => ()
    }

    // Return the library name
    preloadedFile
  }

  /****************************
   * Scala preloading process *
   ****************************/

  private def doPreloadPermissions(
    db: File, s: TaskStreams, library: Library)(implicit androidTarget: AndroidTarget): Unit = {

    // Inform the user
    s.log.info("Setting permissions for " + library.fullName)

    // Generate string from the XML
    val xmlString = scala.xml.Utility.serialize(
      scala.xml.Utility.trim(library.permissionTag),
      minimizeTags=MinimizeMode.Always
    ).toString.replace("\"", "\\\"")

    // Load the file on the device
    androidTarget.run(db, s,
      "shell", "echo", xmlString,
      ">", library.devicePermissionPath
    )
  }

  private def doPreloadJar(
    db: File, dx: File, s: TaskStreams, targetDir: File, library: Library)(implicit androidTarget: AndroidTarget): Unit = {

    // This is the temporary JAR path
    val tempJarPath = (targetDir / library.localJar.name)

    // Dex current Scala library if necessary
    if (tempJarPath.lastModified < library.localJar.lastModified) {
      val dxCmd = Seq(dx.absolutePath,
        "-JXmx1024M",
        "-JXms1024M",
        "-JXss4M",
        "--no-optimize",
        "--debug",
        "--dex",
        "--output=" + tempJarPath.getAbsolutePath,
        library.localJar.getAbsolutePath
      )
      s.log.info  ("Dexing library %s".format(library.fullName))
      s.log.debug (dxCmd.!!)
    }

    // Load the file on the device
    s.log.info("Installing library " + library.fullName)
    androidTarget.run(db, s, "push",
      tempJarPath.getAbsolutePath,
      library.deviceJarPath
    )
  }

  private def doReboot (db: File, s: TaskStreams)(implicit androidTarget: AndroidTarget) = {
      s.log.info("Rebooting " + androidTarget)
      androidTarget match {
        case AndroidDefaultTargets.Emulator => androidTarget.run(db, s, "emu", "kill")
        case _                              => androidTarget.run(db, s, "reboot")
      }
      ()
  }

  private def doRemountReadWrite (db: File, s: TaskStreams)(implicit androidTarget: AndroidTarget) = {
    s.log.info("Remounting /system as read-write")
    androidTarget.run(db, s, "remount")
  }

  /***************************
   * Emulator-specific stuff *
   ***************************/

  private def doStartEmuReadWrite (db: File, s: TaskStreams,
    sdkPath: File, toolsPath: File, avdName: String, verbose: Boolean) = {

    // Find emulator config path
    val avdPath = Path.userHome / ".android" / "avd" / (avdName + ".avd")

    // Open config.ini
    val configFile = avdPath / "config.ini"

    // Read the contents and split by newline
    val configContents = scala.io.Source.fromFile(configFile).mkString

    // Regexp to match the system dir
    val sysre = "image.sysdir.1 *= *(.*)".r 

    sysre findFirstIn configContents match {
      case Some(sysre(sys)) =>

        // Copy system image to the emulator directory if needed
        val rosystem = sdkPath / sys / "system.img"
        val rwsystem = avdPath / "system.img"
        if (!rwsystem.exists) {
          s.log.info("Copying system image")
          "cp %s %s".format(rosystem.getAbsolutePath, rwsystem.getAbsolutePath).!
        }

        // Start the emulator with the local persistent system image
        s.log.info("Starting emulator with read-write system")
        s.log.info("This may take a while...")

        val rwemuCmdF = "%s/emulator -avd %s -no-boot-anim -no-snapshot -qemu -nand system,size=0x1f400000,file=%s -nographic -monitor null"
        val rwemuCmdV = "%s/emulator -avd %s -no-boot-anim -no-snapshot -verbose -qemu -nand system,size=0x1f400000,file=%s -nographic -show-kernel -monitor null"
        val rwemuCmd = (if (!verbose) rwemuCmdF else rwemuCmdV)
          .format(toolsPath, avdName, (avdPath / "system.img").getAbsolutePath)

        s.log.debug (rwemuCmd)
        rwemuCmd.run

        // Remount system as read-write
        val androidTarget = AndroidDefaultTargets.Emulator
        androidTarget.run(db, s, "wait-for-device")
        androidTarget.run(db, s, "remount")

      case None => throw new Exception("Unable to find the system image")
    }
  }

  private def doKillEmu (db: File, s: TaskStreams)(implicit emulator: Boolean) = {
      if (emulator)
        try {
          AndroidDefaultTargets.Emulator.run(db, s, "emu", "kill")
        } catch { case e: RuntimeException => println("emulator false positive") }
      ()
  }

  private def filterLibraries
    (cp: Seq[Attributed[File]], filters: Seq[Attributed[File] => Boolean]) =
      cp filter (ce => filters exists (f => f(ce))) map (f =>
        Library(
          f.data,
          f.get(moduleID.key).map(_.name) getOrElse f.data.name,
          f.get(moduleID.key).map(_.revision) getOrElse "unknown"
        )
      )

  /*******************************
   * Tasks related to preloading *
   *******************************/

  private def preloadDeviceTask = Def.task {
    preloadRunningDeviceTask.value(AndroidDefaultTargets.Device)
  }

  private def preloadTargetTask = Def.inputTask {
    val target = AndroidInstall.adbAllTargetParser(dbPath.value.absolutePath).parsed
    preloadRunningDeviceTask.value(target)
  }

  private def preloadRunningDeviceTask =
    (dbPath, dxPath, target, preloadFilters, managedClasspath, unmanagedClasspath, streams) map {
    (dbPath, dxPath, target, preloadFilters, mcp, umcp, streams) => (at: AndroidTarget) =>
      
      implicit val androidTarget = at
      implicit val emulator = false

      // Retrieve libraries
      val libraries = filterLibraries(mcp ++ umcp, preloadFilters)

      // Wait for the device
      androidTarget.run(dbPath, streams, "wait-for-device")

      // Check for existing libraries
      val librariesToPreload = libraries filterNot { lib =>
        checkPreloadedLibraryVersion(dbPath, streams, lib).isDefined
      }

      // Only do this if we have libraries to preload
      if (!librariesToPreload.isEmpty) {

        // Remount the device in read-write mode
        doRemountReadWrite (dbPath, streams)

        // Preload the libraries
        librariesToPreload map { lib =>

          // Push files to the device
          doPreloadJar         (dbPath, dxPath, streams, target, lib)
          doPreloadPermissions (dbPath, streams, lib)
        }

        // Reboot
        doReboot (dbPath, streams)
      }
    }

  private def preloadEmulatorTask = Def.inputTask {
    val emulatorName = AndroidEmulator.installedAvds.parsed

      // We're using the emulator
      // This appears to be treated elsewhere as "whether emu is running"
      implicit val emulator = true
      implicit val androidTarget = AndroidDefaultTargets.Emulator

      // Retrieve libraries
      val libraries = filterLibraries(managedClasspath.value ++ unmanagedClasspath.value, preloadFilters.value)

      // Don't bother checking if any libraries are already present.
      // We don't even know if the emulator is running, and booting it
      // will certainly be slower than copying a couple of extra jars.
      val librariesToPreload = libraries

      // Only do this if we have libraries to preload
      if (!librariesToPreload.isEmpty) {

        // Kill any running emulator
        doKillEmu (dbPath.value, streams.value)

        // Restart the emulator in system read-write mode
        doStartEmuReadWrite (dbPath.value, streams.value, sdkPath.value, toolsPath.value, emulatorName, false)

        // Preload the libraries
        librariesToPreload map { lib =>

          // Push files to the device
          doPreloadJar         (dbPath.value, dxPath.value, streams.value, target.value, lib)
          doPreloadPermissions (dbPath.value, streams.value, lib)
        }

        // Reboot / Kill emulator
        doKillEmu (dbPath.value, streams.value)
      }
    }

  private def commandTask(command: String)(implicit androidTarget: AndroidTarget) =
    (dbPath, streams) map {
      (d,s) => androidTarget.run(d, s, command)
      ()
    }

  /*************************
   * Insert tasks into SBT *
   *************************/

  lazy val settings: Seq[Setting[_]] = (Seq(
    // Automatically take care of AndroidManifest.xml when needed
    manifestRewriteRules <+= (usePreloaded, managedClasspath, unmanagedClasspath, preloadFilters) map
      { (u, mcp, umcp, l) => UsesLibraryRule(u, filterLibraries(mcp ++ umcp, l)) },

    // Preload Scala on the device/emulator
    preloadDevice <<= preloadDeviceTask,
    preloadEmulator <<= preloadEmulatorTask,
    preloadAny <<= preloadTargetTask
  ))
}
