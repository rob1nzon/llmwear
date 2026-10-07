package dev.veedo.llmwear.wear;

import java.util.ArrayList;
import java.util.List;

final class SpeechInputProviders {
    static final String SAMSUNG = "com.samsung.android.honeyboard";
    static final String GOOGLE = "com.google.android.tts";

    static List<String> available(List<String> installed) {
        List<String> providers = new ArrayList<>();
        if (installed.contains(SAMSUNG)) providers.add(SAMSUNG);
        if (installed.contains(GOOGLE)) providers.add(GOOGLE);
        providers.add("");
        return providers;
    }

    static String choose(String preferred, List<String> installed) {
        List<String> providers = available(installed);
        return providers.contains(preferred) ? preferred : providers.get(0);
    }
}
