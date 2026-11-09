#include <jni.h>
#include <string>
#include <cstdio>
#include <cstdlib>
#include <cstring>

/* Self-contained: no obf.h, no external symbols, nothing that can fail to resolve. */

#define PC5_S2(x) #x
#define PC5_S(x) PC5_S2(x)

/* URL is fixed here so no build variable can point the app back at pc5.example.com. */
static const char* kUrl = "https://pc5-android.onrender.com";

/* Same secret the build already seals raw1/ with (RAW1_KEY), read from the
   compile definition. Works whether CMake passes it quoted or bare. */
static std::string rawKey() {
#ifdef RAW1_KEY
    std::string s = PC5_S(RAW1_KEY);
    if (s.size() >= 2 && s.front() == '"' && s.back() == '"') s = s.substr(1, s.size() - 2);
    if (!s.empty()) return s;
#endif
    return "pc5-dev-default-key-change-me";
}

static jbyteArray n_key(JNIEnv* e, jclass) {
    std::string s = rawKey();
    jbyteArray a = e->NewByteArray((jsize)s.size());
    e->SetByteArrayRegion(a, 0, (jsize)s.size(), (const jbyte*)s.data());
    return a;
}

/* Signature pinning is off (blank = unchecked). Login and injection are unaffected. */
static jstring n_cert(JNIEnv* e, jclass) {
    return e->NewStringUTF("");
}

static jstring n_url(JNIEnv* e, jclass) {
    std::string s = kUrl;
    while (!s.empty() && (s.back() == '/' || s.back() == ' ')) s.pop_back();
    if (s.empty()) s = "https://pc5-android.onrender.com";
    return e->NewStringUTF(s.c_str());
}

static jboolean n_traced(JNIEnv*, jclass) {
    FILE* f = fopen("/proc/self/status", "r");
    if (!f) return JNI_TRUE;
    char line[256];
    bool traced = false;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            traced = atoi(line + 10) != 0;
            break;
        }
    }
    fclose(f);
    return traced ? JNI_TRUE : JNI_FALSE;
}

static JNINativeMethod M[] = {
    {(char*)"key",    (char*)"()[B", (void*)n_key},
    {(char*)"url",    (char*)"()Ljava/lang/String;", (void*)n_url},
    {(char*)"cert",   (char*)"()Ljava/lang/String;", (void*)n_cert},
    {(char*)"traced", (char*)"()Z",  (void*)n_traced}
};

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* e = nullptr;
    if (vm->GetEnv((void**)&e, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = e->FindClass("com/pc5/android/Native");
    if (!c) return JNI_ERR;
    if (e->RegisterNatives(c, M, sizeof(M) / sizeof(M[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
