package com.exteragram.messenger;

import java.util.ArrayList;

/**
 * The slice of exteraGram's config that DEX plugins read. ZaStoGram has no configurable main
 * menu, so the layout lists are empty and plugins that inject into it simply find nothing to extend.
 */
public final class ExteraConfig {

    private static final ArrayList<Integer> mainMenuLayout = new ArrayList<>();
    private static final ArrayList<Integer> mainMenuHiddenItems = new ArrayList<>();

    private ExteraConfig() {
    }

    public static boolean getInAppVibration() {
        return true;
    }

    public static void setInAppVibration(boolean value) {
    }

    public static ArrayList<Integer> getMainMenuLayout() {
        return mainMenuLayout;
    }

    public static ArrayList<Integer> getDefaultMainMenuLayout() {
        return new ArrayList<>();
    }

    public static ArrayList<Integer> getMainMenuHiddenItems() {
        return mainMenuHiddenItems;
    }

    public static void saveMainMenuLayout() {
    }

    public static android.content.SharedPreferences getPreferences() {
        return org.telegram.messenger.ApplicationLoader.applicationContext
                .getSharedPreferences("exteraconfig", android.content.Context.MODE_PRIVATE);
    }

    public static android.content.SharedPreferences.Editor getEditor() {
        return getPreferences().edit();
    }

    public static boolean getPluginsEngine() {
        return true;
    }

    public static boolean getPluginsSafeMode() {
        return false;
    }

    /** Avatar corner radius in px for an avatar {@code size} dp wide: ZaStoGram avatars are round. */
    public static int getAvatarCorners(float size) {
        return getAvatarCorners(size, false, false);
    }

    public static int getAvatarCorners(float size, boolean inPixels) {
        return getAvatarCorners(size, inPixels, false);
    }

    public static int getAvatarCorners(float size, boolean inPixels, boolean forum) {
        float px = inPixels ? size : org.telegram.messenger.AndroidUtilities.dp(size);
        return (int) (forum ? px * 0.3f : px / 2f);
    }
}
