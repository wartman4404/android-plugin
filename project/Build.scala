object NativeHelper {
  @native def pathconf(path: String, name: Int): Long
  @native protected def getPathconfArgs(): _PathconfArgs

  lazy val PATHCONF_ARGS = getPathconfArgs()

  class _PathconfArgs {
    val LINK_MAX: Int = 0
    val NAME_MAX: Int = 0
    val PATH_MAX: Int = 0
    val PIPE_BUF: Int = 0
  }
}
