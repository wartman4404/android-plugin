scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xcheckinit", "-feature")

//, "-Xfatal-warnings")

scalaVersion := "2.10.3"

sbtPlugin := true

lazy val compileNative = taskKey[sbt.inc.Analysis]("Compiles native sources.")

lazy val cleanNative = taskKey[Unit]("Deletes files generated from native sources.")

lazy val processLogger = sbt.Def.task {
  new sbt.ProcessLogger() {
    override def buffer[T](f: => T): T = f
    override def error(s: => String): Unit = streams.value.log.warn(s)
    override def info(s: => String): Unit = streams.value.log.info(s)
  }
}

lazy val environment = Def.task {
  val basepath = (managedClasspath in Compile).value
  val classpath = (classDirectory in Compile).value
  Seq(
    "COMPILE_PATH" -> classpath.toString,
    "CLASSPATH" -> (classpath +: basepath).mkString(":"),
    "JAVA_HOME" -> new java.io.File(System.getProperty("java.home")).getParent(),
    "NATIVE_DIR" -> target.value.toString
  )
}

cleanNative := {
  val result = sbt.Process("make -f Makefile.native clean",
    None,
    environment.value: _*
  ) !< processLogger.value
  if (result != 0)
    sys.error("error cleaning native library")
}

compileNative := {
  val result = sbt.Process("make -f Makefile.native all",
    None,
    environment.value: _*
  ) !< processLogger.value
  if (result != 0)
    sys.error("error compiling native library")
  sbt.inc.Analysis.Empty
}

clean := {
  val _ = cleanNative.value
  clean.value
}

compile <<= (compile in Compile) dependsOn compileNative
