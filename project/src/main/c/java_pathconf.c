#include <jni.h>
#include <unistd.h>
#include <stdio.h>

jlong JNICALL java_pathconf(JNIEnv *env, jobject thiz, jstring path, jint name) {
  const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
  long result = pathconf(cpath, name);
  (*env)->ReleaseStringUTFChars(env, path, cpath);
  return result;
}

void set_args_field(JNIEnv *env, jclass args_class, jobject args, const char *classname, const char *name, int value) {
  jfieldID field = (*env)->GetFieldID(env, args_class, name, "I");
  if (field == NULL) {
    fprintf(stderr, "failed to get int field '%s' of class %s\n", name, classname);
    return;
  }
  (*env)->SetIntField(env, args, field, value);
}

JNIEXPORT jobject JNICALL get_pathconf_args(JNIEnv *env, jobject thiz) {
  const char *classname = "NativeHelper$_PathconfArgs";
  jclass args_class = (*env)->FindClass(env, classname);
  if (args_class == NULL) {
    fprintf(stderr, "failed to get %s class\n", classname);
    return NULL;
  }
  jmethodID constructor = (*env)->GetMethodID(env, args_class, "<init>", "()V");
  if (constructor == NULL) {
    fprintf(stderr, "failed to find constructor for %s\n", classname);
    return NULL;
  }
  jobject args = (*env)->NewObject(env, args_class, constructor);
  set_args_field(env, args_class, args, classname, "LINK_MAX", _PC_LINK_MAX);
  set_args_field(env, args_class, args, classname, "PATH_MAX", _PC_PATH_MAX);
  set_args_field(env, args_class, args, classname, "NAME_MAX", _PC_NAME_MAX);
  set_args_field(env, args_class, args, classname, "PIPE_BUF", _PC_PIPE_BUF);
  return args;
}

jboolean registerNatives(JNIEnv *env, const char *classname) {
  JNINativeMethod methods[] = {
    {"pathconf", "(Ljava/lang/String;I)J", (void*) java_pathconf },
    { "getPathconfArgs", "()LNativeHelper$_PathconfArgs;", (void*) get_pathconf_args }
  };
  jclass nativehelper = (*env)->FindClass(env, classname);
  if (nativehelper == NULL) {
    fprintf(stderr, "couldn't find class %s\n", classname);
    return JNI_FALSE;
  }
  int result = (*env)->RegisterNatives(env, nativehelper, methods, sizeof methods / sizeof methods[0]);
  if (result < 0) {
    fprintf(stderr, "failed registering methods on %s\n", classname);
    return JNI_FALSE;
  } else {
    return JNI_TRUE;
  }
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  JNIEnv *env = NULL;
  jint result = (*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_2);
  if (result != JNI_OK) {
    fprintf(stderr, "couldn't get JNI enviroment\n");
    return JNI_ERR;
  }
  if (registerNatives(env, "NativeHelper$") == JNI_FALSE) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_4;
}

