#include <jni.h>
#include <string>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "obf.h"

/* URL is hardcoded here on purpose: it compiles no matter what CMake or obf.h
   contain, and a stale OBF_URL can never send the app back to pc5.example.com. */
static const char* kUrl = "https://pc5-android.onrender.com";

static std::string dec(const unsigned char* d, int n) {
    std::string s;
    s.resize(n);
    for (int i = 0; i < n; i++) s[i] = (char)(d[i] ^ (unsigned char)(0x5A + i * 7));
    return s;
}

static jbyteArray arr(JNIEnv* e, const unsigned char* d, int n) {
    std::string s = dec(d, n);
    jbyteArray a = e->NewByteArray((jsize)s.size());
    e->SetByteArrayRegion(a, 0, (jsize)s.size(), (const jbyte*)s.data());
    return a;
}

static jstring str(JNIEnv* e, const unsigned char* d, int n) {
    std::string s = dec(d, n);
    return e->NewStringUTF(s.c_str());
}

static jbyteArray n_key(JNIEnv* e, jclass)  { return arr(e, OBF_KEY,  OBF_KEY_LEN);  }
static jstring   n_cert(JNIEnv* e, jclass)  { return str(e, OBF_CERT, OBF_CERT_LEN); }

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
