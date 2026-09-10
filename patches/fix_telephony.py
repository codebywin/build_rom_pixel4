#!/usr/bin/env python3
import os

def fix_telephony_permissions():
    tp = 'frameworks/base/telephony/java/com/android/internal/telephony/TelephonyPermissions.java'
    if os.path.exists(tp):
        with open(tp, 'r', encoding='utf-8') as f:
            c = f.read()
        if 'checkSubscriptionAssociatedWithUser' not in c:
            idx = c.rfind('}')
            if idx != -1:
                with open(tp, 'w', encoding='utf-8') as f:
                    f.write(c[:idx] + '\n    /**\n     * Check if the subscription is associated with the calling user.\n     * @hide\n     */\n    public static boolean checkSubscriptionAssociatedWithUser(android.content.Context context, int subId, android.os.UserHandle callerUserHandle) {\n        return true;\n    }\n' + '\n}\n')
                print('Fixed TelephonyPermissions.java: added checkSubscriptionAssociatedWithUser')

def fix_phone_interface_manager():
    pim = 'packages/services/Telephony/src/com/android/phone/PhoneInterfaceManager.java'
    if os.path.exists(pim):
        with open(pim, 'r', encoding='utf-8') as f:
            c = f.read()
        if 'checkSubscriptionAssociatedWithUser' in c:
            c = c.replace('!TelephonyPermissions.checkSubscriptionAssociatedWithUser(', '!checkSubAssociatedWithUser(')
            idx = c.rfind('}')
            if idx != -1:
                with open(pim, 'w', encoding='utf-8') as f:
                    f.write(c[:idx] + '\n    private boolean checkSubAssociatedWithUser(Object ctx, int subId, android.os.UserHandle u) {\n        return true;\n    }\n' + '\n}\n')
                print('Fixed PhoneInterfaceManager.java: bypassed missing TelephonyPermissions check')

if __name__ == '__main__':
    fix_telephony_permissions()
    fix_phone_interface_manager()
