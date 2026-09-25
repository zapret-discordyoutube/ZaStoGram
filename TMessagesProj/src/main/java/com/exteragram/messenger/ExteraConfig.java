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
}
