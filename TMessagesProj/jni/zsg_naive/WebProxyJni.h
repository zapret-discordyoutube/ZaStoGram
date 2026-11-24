/* JNI glue of the WebView loopback proxy (jni/zsg_naive/WebProxy.cpp): registers the natives of
 * org.telegram.zastogram.naive.NaiveWebProxy. Called once from registerNativeTgNetFunctions (TgNetWrapper.cpp). */
#ifndef ZSG_NAIVE_WEB_PROXY_JNI_H
#define ZSG_NAIVE_WEB_PROXY_JNI_H

#include <jni.h>

namespace tgnet {
namespace naive {

bool RegisterWebProxyJni(JNIEnv *env);

} // namespace naive
} // namespace tgnet

#endif
