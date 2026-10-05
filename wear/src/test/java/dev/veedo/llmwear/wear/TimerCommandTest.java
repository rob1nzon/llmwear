package dev.veedo.llmwear.wear;

import org.junit.Test;
import dev.veedo.llmwear.commands.WeatherCommand;
import static org.junit.Assert.*;

public class TimerCommandTest {
    @Test public void digitsAndWords() {
        assertEquals(300, TimerCommand.seconds("Поставь таймер на 5 минут"));
        assertEquals(300, TimerCommand.seconds("таймер на пять минут"));
        assertEquals(1500, TimerCommand.seconds("таймер на двадцать пять минут"));
        assertEquals(90, TimerCommand.seconds("таймер на полторы минуты"));
        assertEquals(1800, TimerCommand.seconds("таймер на полчаса"));
        assertEquals(3600, TimerCommand.seconds("таймер на час"));
    }
    @Test public void combinations() {
        assertEquals(4200, TimerCommand.seconds("таймер на один час и десять минут"));
        assertEquals(62, TimerCommand.seconds("таймер на одну минуту две секунды"));
        assertEquals(30, TimerCommand.seconds("таймер на 30 секунд."));
    }
    @Test public void rejectsUnsafeOrAmbiguousCommands() {
        assertFalse(TimerCommand.isTimer("не ставь таймер на пять минут"));
        assertEquals(-1, TimerCommand.seconds("таймер на -5 минут"));
        assertEquals(-1, TimerCommand.seconds("таймер на ноль минут"));
        assertEquals(-1, TimerCommand.seconds("таймер на 30 часов"));
        assertEquals(-1, TimerCommand.seconds("таймер на 5 минут завтра"));
        assertEquals(-1, TimerCommand.seconds("таймер"));
    }
    @Test public void weatherRouting() {
        assertTrue(WeatherCommand.isWeather("Какая погода завтра?"));
        assertTrue(WeatherCommand.isWeather("погода"));
        assertFalse(WeatherCommand.isWeather("напиши рассказ о погоде"));
    }
}
