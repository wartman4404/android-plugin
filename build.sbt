name := "sbt-android"

organization := "org.scala-sbt"

version := "0.7.1-SNAPSHOT"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xcheckinit", "-Xmax-classfile-name", s"${maxFilename.value}")

//, "-Xfatal-warnings")

scalaVersion := "2.10.3"

publishMavenStyle := false

publishTo <<= (version) { version: String =>
    val scalasbt = "http://scalasbt.artifactoryonline.com/scalasbt/"
    val (name, url) = if (version.contains("-"))
                        ("sbt-plugin-snapshots", scalasbt+"sbt-plugin-snapshots")
                      else
                        ("sbt-plugin-releases", scalasbt+"sbt-plugin-releases")
    Some(Resolver.url(name, new URL(url))(Resolver.ivyStylePatterns))
}

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

libraryDependencies ++= Seq(
  "com.google.android.tools" % "ddmlib" % "r13",
  "net.sf.proguard" % "proguard-base" % "4.11"
)

sbtPlugin := true

commands += Status.stampVersion

lazy val native = settingKey[NativeHelper.type]("native helper")

native := {
  def changeLibraryPath(path: String) = {
    System.setProperty("java.library.path", path)
    val fieldSysPath = classOf[ClassLoader].getDeclaredField("sys_paths")
    fieldSysPath.setAccessible( true )
    fieldSysPath.set( null, null )
  }
  def withLibraryPath(path: String)(fn: =>Unit) = {
    val oldPath = System.getProperty("java.library.path")
    changeLibraryPath(s"$oldPath:$path")
    fn
    changeLibraryPath(oldPath)
  }
  withLibraryPath("project/target/so") {
    System.loadLibrary("java_pathconf")
  }
  NativeHelper
}

lazy val maxFilename = Def.setting {
  native.value.pathconf(target.value.toString, native.value.PATHCONF_ARGS.NAME_MAX)
}
