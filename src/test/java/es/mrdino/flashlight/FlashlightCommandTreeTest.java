package es.mrdino.flashlight;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlashlightCommandTreeTest {
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private CommandSourceStack source;
    private FlashlightCommand executor;
    private Player player;

    @BeforeEach void setup() {
        executor = mock(FlashlightCommand.class);
        player = mock(Player.class);
        source = mock(CommandSourceStack.class);
        var sender = mock(CommandSender.class);
        when(sender.hasPermission(anyString())).thenReturn(true);
        when(source.getSender()).thenReturn(sender);
        ArgumentType<PlayerSelectorArgumentResolver> targets = reader -> {
            while (reader.canRead() && reader.peek() != ' ') reader.skip();
            return stack -> { assertSame(source, stack); return List.of(player); };
        };
        dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new FlashlightCommandTree(executor).build(targets));
    }

    @Test void guideShowsNamedArgumentsAndTooltipExplainsDurationAndStrength() throws Exception {
        var root = dispatcher.getRoot().getChild("flashlight");
        var targets = root.getChild("flicker").getChild("targets");
        var duration = targets.getChild("duration");
        assertNotNull(duration.getChild("strength_0_100").getChild("pulse_interval"));
        var usage = dispatcher.getSmartUsage(duration, source);
        assertTrue(usage.values().stream().anyMatch(value -> value.contains("strength_0_100") && value.contains("pulse_interval")));
        var hints = dispatcher.getCompletionSuggestions(dispatcher.parse("flashlight flicker Explorer ", source)).get().getList();
        assertTrue(hints.stream().anyMatch(hint -> hint.getText().equals("3s") && hint.getTooltip().getString().contains("seconds")));
        var strengths = dispatcher.getCompletionSuggestions(dispatcher.parse("flashlight flicker Explorer 3s ", source)).get().getList();
        assertTrue(strengths.stream().anyMatch(hint -> hint.getText().equals("100") && hint.getTooltip().getString().contains("blackouts")));
    }

    @Test void completeCommandResolvesPlayersWithTheExecutionContextAndReturnsTheAffectedCount() throws Exception {
        when(executor.execute(any(), any(), any())).thenReturn(2);
        assertEquals(2, dispatcher.execute("flashlight flicker @a[distance=..10] 5s 85 4t", source));
        verify(executor).execute(eq(source.getSender()), aryEq("flicker", "<targets>", "5s", "85.0", "4t"), eq(List.of(player)));
    }

    private String[] aryEq(String... values) { return org.mockito.AdditionalMatchers.aryEq(values); }

    @Test void parameterRangesAndPermissionsAreEnforcedBeforeExecuting() throws Exception {
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> dispatcher.execute("flashlight flicker Explorer 3s 101", source));
        when(source.getSender().hasPermission("flashlight.admin")).thenReturn(false);
        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> dispatcher.execute("flashlight off Explorer", source));
        verifyNoInteractions(executor);
    }
}
