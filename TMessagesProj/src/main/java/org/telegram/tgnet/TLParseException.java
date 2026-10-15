package org.telegram.tgnet;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;

public class TLParseException extends RuntimeException {
    private TLParseException(String message) {
        super(message);
    }

    public static boolean isRpcDropAnswerConstructor(int constructorId) {
        return constructorId == 0x5e2ad36e
                || constructorId == 0xa43ad8b7
                || constructorId == 0xcd78e586;
    }

    public static void doThrowOrLog(InputSerializedData stream, String tlTypeName, int constructorId, boolean throwEnabled) {
        final TLDataSourceType dataSourceType = stream != null ? stream.getDataSourceType() : null;
        final String message = String.format("can't parse magic %x in %s. Source: %s", constructorId, tlTypeName, dataSourceType);
        final TLParseException tlParseException = new TLParseException(message);
        final boolean rpcDropAnswer = isRpcDropAnswerConstructor(constructorId);

        if (rpcDropAnswer) {
            FileLog.d("tl_parse_drop_answer raw_constructor=0x" + Integer.toHexString(constructorId)
                    + " expected_response=" + tlTypeName
                    + " source=" + dataSourceType
                    + " action=defer_to_request_context");
        } else {
            FileLog.e(tlParseException, true);
        }
        if (BuildConfig.DEBUG_VERSION && !rpcDropAnswer && constructorId != 0xd18be2ef && constructorId != 0xf35c6d01) {
            AndroidUtilities.runOnUIThread(() -> {
                NotificationCenter.getGlobalInstance()
                    .postNotificationName(NotificationCenter.tlSchemeParseException, tlParseException);
            });
        }

        if (throwEnabled) {
            throw tlParseException;
        }
    }
}
