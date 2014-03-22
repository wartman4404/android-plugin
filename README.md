You are probably looking for the official [sbt-android-plugin][].

---

This fork has the following changes:

- The `preload-emulator` command works on the official emulator
- Targets can be set from the sbt command line using `adb-set-target`
- adb no longer blocks indefinitely when no devices are connected
- Output from adb is displayed as it's running
- ndk-build is called from &lt;project-root&gt;/jni rather than &lt;project-root&gt;

[sbt-android-plugin]: https://github.com/jberkel/android-plugin.
