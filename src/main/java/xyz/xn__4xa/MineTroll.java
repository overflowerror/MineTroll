package xyz.xn__4xa;

import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import com.theokanning.openai.utils.TikTokensUtil;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.concurrent.Task;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class MineTroll extends JavaPlugin implements Listener {
    static private final String DISCORD_BOT_TOKEN_PATH = "discord.token";
    static private final String DISCORD_GUILD_ID_PATH = "discord.guild";
    static private final String OPENAI_TOKEN_PATH = "openai.token";

    static private final String SYSTEM_PROMPT = """
            Please come up with a somewhat mean-spirited comment regarding a player's death in Minecraft. The comment should be short. It should be in German. Do not use a formal tone - be casual, use colloquial language.
            You can refer to the player name by using this placeholder: {}
            
            Examples:
            
            Hey {}, du weißt schon dass man hin und wieder auch Luft holen sollte?
            
            lol, was für ein noob
            
            Das war beeindruckened! Im negativen Sinne.
            
            War das Absicht oder einfach nur Inkompetenz?
            
            Versuch's doch mal mit einer anderen Strategie... Oder überhaupt einer.
            
            Ha! {} ist gegen 'ne Wand geflogen - so ein Anfänger.
            
            Na {}? Ist der Drache so einfach zu besiegen wie du dachtest?
            
            Ich frage mich ja, ob {} das wohl absichtlich macht...
            
            Da war 'ne Diamant-Spitzhacke dabei, oder? Das tut weh.
            
            Ups. ^^
            """;

    static private final String USER_PROMPT = """
            Death Message: %s
            Levels lost: %d
            Inventory lost: %s
            """;

    final static private String MESSAGE_FORMAT =
            "[<aqua>Discord</aqua> | %rolecolor%%role%<reset>] %name% » %message%";
    final static private String PLACEHOLDER_ROLE = "%role%";
    final static private String PLACEHOLDER_ROLE_COLOR = "%rolecolor%";
    final static private String PLACEHOLDER_MESSAGE = "%message%";
    final static private String PLACEHOLDER_NAME = "%name%";

    private JDA discord;
    private OpenAiService openai;

    private final Random random = new Random();

    @Override
    public void onEnable() {
        FileConfiguration config = getConfig();
        config.addDefault(DISCORD_BOT_TOKEN_PATH, "Discord token");
        config.addDefault(DISCORD_GUILD_ID_PATH, "Discord guild id");
        config.addDefault(OPENAI_TOKEN_PATH, "OpenAI token");
        config.options().copyDefaults(true);
        saveConfig();


        discord = JDABuilder.createLight(
                config.getString(DISCORD_BOT_TOKEN_PATH, ""),
                GatewayIntent.GUILD_MEMBERS
            ).build();

        openai = new OpenAiService(config.getString(OPENAI_TOKEN_PATH, ""));

        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private Member getRandomMember() {
        List<Member> members = Optional.ofNullable(
                    discord.getGuildById(getConfig().getString(DISCORD_GUILD_ID_PATH, ""))
                )
                .map(Guild::loadMembers)
                .map(Task::get)
                .orElse(List.of())
                .stream()
                .filter(m -> !m.getUser().isBot())
                .toList();
        return members.get(random.nextInt(members.size()));
    }

    private Role getRole(Member member) {
        List<Role> roles = member.getRoles();

        if (roles.isEmpty()) {
            return member.getGuild().getPublicRole();
        }

        return roles.stream().max(Comparator.comparing(Role::getPosition)).get();
    }

    private String getMessage(PlayerDeathEvent event) {
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest
            .builder()
            .model(TikTokensUtil.ModelEnum.GPT_3_5_TURBO.getName())
            .messages(List.of(
                new ChatMessage(ChatMessageRole.SYSTEM.value(), SYSTEM_PROMPT),
                new ChatMessage(ChatMessageRole.USER.value(), USER_PROMPT.formatted(
                    event.deathMessage() instanceof TranslatableComponent component
                        ? component.key()
                        : "unknown",
                    event.getPlayer().getLevel(),
                    event.getDrops()
                        .stream()
                        .map(i -> i.getAmount() + "x " + i.getType().name().toLowerCase())
                        .collect(Collectors.joining(", "))
                ))
            ))
            .build();

        ChatCompletionResult result = openai.createChatCompletion(chatCompletionRequest);

        return result
            .getChoices().get(0)
            .getMessage().getContent()
            .replaceAll("[{][^}]*[}]", event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerDie(PlayerDeathEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Member member = getRandomMember();
            String message = getMessage(event);
            Role role = getRole(member);

            String richMessage = MESSAGE_FORMAT
                .replaceAll(PLACEHOLDER_ROLE, role.getName())
                .replaceAll(PLACEHOLDER_ROLE_COLOR,
                    "<" + getClosestColor(Objects.requireNonNullElse(role.getColor(), Color.DARK_GRAY)) + ">")
                .replaceAll(PLACEHOLDER_NAME, member.getEffectiveName())
                .replaceAll(PLACEHOLDER_MESSAGE, message);

            getLogger().info(event.getPlayer().getName() + ": " + member.getEffectiveName() + ": " + message);

            event.getPlayer().sendRichMessage(richMessage);
        }, 20 * 5);
    }

    private String getClosestColor(Color color) {
        return Arrays.stream(MinecraftColor.values())
            .map(c -> new ColorTuple(c, (
                    Math.abs(c.r - color.getRed()) +
                    Math.abs(c.g - color.getGreen()) +
                    Math.abs(c.b - color.getBlue())
                ))
            ).min(Comparator.comparing(t -> t.difference))
            .map(ColorTuple::color)
            .orElse(MinecraftColor.BLACK)
            .name;
    }

    private record ColorTuple(MinecraftColor color, int difference) {}

    private enum MinecraftColor {
        BLACK("black", 0, 0, 0),
        DARKBLUE("dark_blue", 0, 0, 170),
        DARKGREEN("dark_green", 0, 170, 0),
        DARKAQUA("dark_aqua", 0, 170, 170),
        DARKRED("dark_red", 170, 0, 0),
        DARKPURPLE("dark_purple", 170, 0, 170),
        GOLD("gold", 255, 170, 0),
        GREY("gray", 170, 170, 170),
        DARKGREY("dark_gray", 85, 85, 85),
        BLUE("blue", 85, 85, 255),
        GREEN("green", 85, 255, 85),
        AQUA("aqua", 85, 255, 255),
        RED("red", 255, 85, 85),
        LIGHTPURPLE("light_purple", 255, 85, 255),
        YELLOW("yellow", 255, 255, 85),
        WHITE("white", 255, 255, 255),
        MINECOIN_GOLD("minecoin_gold", 221, 214, 5),
        ;

        final String name;
        final int r, g, b;

        MinecraftColor(String name, int r, int g, int b) {
            this.name = name;
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }
}