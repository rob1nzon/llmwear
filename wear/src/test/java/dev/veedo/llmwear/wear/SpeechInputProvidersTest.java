package dev.veedo.llmwear.wear;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class SpeechInputProvidersTest {
    @Test public void offersOnlyInstalledProvidersAndSystemChoice() {
        assertEquals(List.of(SpeechInputProviders.SAMSUNG, SpeechInputProviders.GOOGLE, ""),
                SpeechInputProviders.available(List.of(SpeechInputProviders.GOOGLE,
                        "com.google.android.inputmethod.latin", SpeechInputProviders.SAMSUNG)));
    }

    @Test public void honorsGoogleAndSystemPreferences() {
        List<String> installed = List.of(SpeechInputProviders.SAMSUNG, SpeechInputProviders.GOOGLE);
        assertEquals(SpeechInputProviders.GOOGLE,
                SpeechInputProviders.choose(SpeechInputProviders.GOOGLE, installed));
        assertEquals("", SpeechInputProviders.choose("", installed));
    }

    @Test public void fallsBackWhenSamsungIsMissing() {
        assertEquals(SpeechInputProviders.GOOGLE, SpeechInputProviders.choose(
                SpeechInputProviders.SAMSUNG, List.of(SpeechInputProviders.GOOGLE)));
        assertEquals("", SpeechInputProviders.choose(SpeechInputProviders.SAMSUNG, List.of()));
    }

    @Test public void unknownPreferenceDefaultsToSamsungIfAvailable() {
        assertEquals(SpeechInputProviders.SAMSUNG,
                SpeechInputProviders.choose("unknown", List.of(SpeechInputProviders.SAMSUNG)));
    }
}
