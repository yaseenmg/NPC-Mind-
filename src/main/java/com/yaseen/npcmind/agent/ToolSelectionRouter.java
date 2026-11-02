package com.yaseen.npcmind.agent;

import com.yaseen.npcmind.tool.AITool;
import com.yaseen.npcmind.tool.ToolContract;
import com.yaseen.npcmind.tool.ToolRegistry;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.yaseen.npcmind.api.Internal;

/**
 * Local-only command router for NPC Mind.  It never calls a remote model or server.
 * The goal is to resolve ordinary English and Arabic requests to a known tool and a
 * schema-valid argument object before the world executor is reached.
 */
@Internal
public final class ToolSelectionRouter {
    public record Route(String tool, String arguments, String reason) {}
    private record Rule(String[] keys, String tool, String arguments, String reason) {}

    private static final Pattern COORDINATES = Pattern.compile(
            "(?<![A-Za-z0-9_])([+-]?\\d{1,8})\\s*(?:,|\\s)\\s*([+-]?\\d{1,3})\\s*(?:,|\\s)\\s*([+-]?\\d{1,8})(?![A-Za-z0-9_])");
    private static final Pattern COUNT = Pattern.compile("(?<!\\d)(\\d{1,3})(?!\\d)");
    private static final List<Rule> EXTENSIONS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Built-ins are deliberately ordered from specific commands to general commands. */
    private static final Rule[] RULES = {
            new Rule(new String[]{"collect xp", "collect experience", "pick up xp", "experience orbs", "اجمع الخبرة", "اجمع الاكس بي", "اجمع الاكسبي"},
                    "collect_xp", "{\"action\":\"enable\",\"ticks\":400}", "experience-pickup intent"),
            new Rule(new String[]{"player state", "show xp", "show experience", "how much xp", "حالة اللاعب", "مستوى الخبرة", "كم خبرة"},
                    "manage_player_state", "{\"action\":\"status\"}", "player-like-state intent"),
            new Rule(new String[]{"throw ender pearl", "use ender pearl", "pearl escape", "throw a pearl", "ارم لؤلؤة النهاية", "ارمي لؤلؤة النهاية", "استخدم لؤلؤة النهاية", "اهرب باللؤلؤة"},
                    "throw_ender_pearl", "{\"mode\":\"auto\"}", "ender-pearl tactical intent"),
            new Rule(new String[]{"fill water bucket", "fill bucket", "collect water bucket", "املأ الدلو", "املا الدلو", "املأ دلو الماء", "اجمع ماء بالدلو"},
                    "fill_bucket", "{\"fluid\":\"water\"}", "water-bucket acquisition intent"),
            new Rule(new String[]{"stop following", "stay here", "wait here", "don't follow", "do not follow", "قف مكانك", "ابق هنا", "ابقي هنا", "توقف عن الاتباع", "لا تتبعني"},
                    "follow_owner", "{\"enabled\":false}", "stop-follow intent"),
            new Rule(new String[]{"follow me", "come with me", "follow the player", "اتبعني", "اتبعنى", "تعال معي", "تعالي معي", "امش وراي", "امش ورائي"},
                    "follow_owner", "{\"enabled\":true}", "follow intent"),
            new Rule(new String[]{"come here", "come to me", "come over", "تعال هنا", "تعالي هنا", "تعال الي", "تعال إلي", "اقترب", "تعال"},
                    "move_to", "{}", "move-to-owner intent"),
            new Rule(new String[]{"guard me", "protect me", "protect the owner", "احمني", "احميني", "احرسني", "كن حارسي"},
                    "bodyguard_mode", "{\"enabled\":true}", "guard intent"),
            new Rule(new String[]{"stop attacking", "stop fighting", "cease fire", "stop combat", "توقف عن القتال", "كفى قتال", "لا تقاتل", "لا تهاجم"},
                    "combat_target", "{\"mode\":\"stop\"}", "stop-combat intent"),
            new Rule(new String[]{"kill sheep", "kill a sheep", "hunt sheep", "hunt a sheep", "اقتل الخروف", "اصطد الخروف"},
                    "combat_target", "{\"mode\":\"nearest_animal\",\"animal\":\"sheep\"}", "sheep-hunting intent"),
            new Rule(new String[]{"kill cow", "kill a cow", "hunt cows", "hunt cow", "اقتل البقرة", "اصطد البقرة"},
                    "combat_target", "{\"mode\":\"nearest_animal\",\"animal\":\"cow\"}", "cow-hunting intent"),
            new Rule(new String[]{"kill pig", "kill a pig", "hunt pigs", "hunt pig", "اقتل الخنزير", "اصطد الخنزير"},
                    "combat_target", "{\"mode\":\"nearest_animal\",\"animal\":\"pig\"}", "pig-hunting intent"),
            new Rule(new String[]{"kill chicken", "kill a chicken", "hunt chickens", "hunt chicken", "اقتل الدجاج", "اصطد الدجاج"},
                    "combat_target", "{\"mode\":\"nearest_animal\",\"animal\":\"chicken\"}", "chicken-hunting intent"),
            new Rule(new String[]{"hunt animals", "hunt animal", "hunt for food", "صيد الحيوانات", "اصطد الحيوانات", "اجمع اللحم"},
                    "combat_target", "{\"mode\":\"nearest_animal\"}", "animal-hunting intent"),
            new Rule(new String[]{"attack mobs", "attack enemies", "fight", "defend me", "kill the", "kill", "attack", "اقتل", "هاجم", "هجوم", "قاتل", "دافع عني", "اضرب"},
                    "combat_target", "{\"mode\":\"nearest_hostile\"}", "combat intent"),
            new Rule(new String[]{"stop exploring", "stop explore", "توقف عن الاستكشاف", "لا تستكشف"},
                    "explore_mode", "{\"enabled\":false}", "stop-exploration intent"),
            new Rule(new String[]{"explore", "scout", "look around for places", "استكشف", "اكتشف", "روح استكشف"},
                    "explore_mode", "{\"enabled\":true}", "exploration intent"),
            new Rule(new String[]{"gather wood", "chop wood", "collect wood", "get wood", "cut tree", "logs", "wood", "اجمع خشب", "احضر خشب", "اقطع شجر", "اقطع الشجر", "خشب", "جذوع"},
                    "gather_resources", "{\"resource\":\"wood\",\"count\":8}", "wood intent"),
            new Rule(new String[]{"gather resources", "collect resources", "get resources", "اجمع موارد", "اجمع الموارد", "اجمع مواد", "جمع موارد"},
                    "gather_resources", "{\"count\":8}", "resource intent"),
            new Rule(new String[]{"find a village", "find village", "search for village", "look for a village", "ابحث عن قرية", "دور على قرية", "ابحث عن القرى", "قرية"},
                    "search_structure", "{\"structure\":\"village\"}", "village-search intent"),
            new Rule(new String[]{"find a stronghold", "search stronghold", "stronghold", "معقل", "حصن النهاية"},
                    "search_structure", "{\"structure\":\"stronghold\"}", "stronghold-search intent"),
            new Rule(new String[]{"find a fortress", "nether fortress", "fortress", "قلعة النذر", "حصن النذر"},
                    "search_structure", "{\"structure\":\"fortress\"}", "fortress-search intent"),
            new Rule(new String[]{"find an ancient city", "ancient city", "المدينة القديمة"},
                    "search_structure", "{\"structure\":\"ancient_city\"}", "ancient-city-search intent"),
            new Rule(new String[]{"find a mansion", "woodland mansion", "mansion", "القصر"},
                    "search_structure", "{\"structure\":\"mansion\"}", "mansion-search intent"),
            new Rule(new String[]{"find a monument", "ocean monument", "monument", "النصب البحري"},
                    "search_structure", "{\"structure\":\"monument\"}", "monument-search intent"),
            new Rule(new String[]{"find a shipwreck", "shipwreck", "حطام سفينة", "سفينة غارقة"},
                    "search_structure", "{\"structure\":\"shipwreck\"}", "shipwreck-search intent"),
            new Rule(new String[]{"find a mineshaft", "mineshaft", "منجم مهجور"},
                    "search_structure", "{\"structure\":\"mineshaft\"}", "mineshaft-search intent"),
            new Rule(new String[]{"find a bastion", "bastion", "حصن الباستيون"},
                    "search_structure", "{\"structure\":\"bastion\"}", "bastion-search intent"),
            new Rule(new String[]{"find a desert temple", "desert temple", "desert pyramid", "معبد الصحراء", "هرم الصحراء"},
                    "search_structure", "{\"structure\":\"desert_pyramid\"}", "desert-temple-search intent"),
            new Rule(new String[]{"harvest", "farm the", "reap", "collect crops", "احصد", "احصدي", "اجمع المحصول", "احصد الزرع"},
                    "farm_area", "{\"radius\":6,\"replant\":true}", "harvest intent"),
            new Rule(new String[]{"plant", "till", "sow", "ازرع", "احرث", "ازرعي", "احرثي", "ازرع بذور"},
                    "till_and_plant", "{\"radius\":4}", "plant intent"),
            new Rule(new String[]{"cook", "cook food", "smelt food", "اطبخ", "اطبخي", "اشو", "حمص"},
                    "cook_food", "{\"output\":\"\"}", "cook intent"),
            new Rule(new String[]{"build a house", "build house", "make a house", "ابن بيت", "ابني بيت", "ابن لي بيت", "ابني لي بيت", "بناء بيت"},
                    "build_pattern", "{\"pattern\":\"house\"}", "house-build intent"),
            new Rule(new String[]{"build a tower", "build tower", "tower", "ابن برج", "ابني برج", "برج"},
                    "build_pattern", "{\"pattern\":\"tower\"}", "tower-build intent"),
            new Rule(new String[]{"build a bridge", "build bridge", "bridge", "ابن جسر", "ابني جسر", "جسر"},
                    "build_pattern", "{\"pattern\":\"bridge\"}", "bridge-build intent"),
            new Rule(new String[]{"build a floor", "build floor", "floor", "ابن ارضية", "ابني ارضية", "أرضية"},
                    "build_pattern", "{\"pattern\":\"floor\"}", "floor-build intent"),
            new Rule(new String[]{"build a wall", "build wall", "wall", "ابن جدار", "ابني جدار", "ابن سور", "ابني سور", "جدار", "سور"},
                    "build_pattern", "{\"pattern\":\"wall\"}", "wall-build intent"),
            new Rule(new String[]{"place torch", "place torches", "light up", "put down torches", "اضئ", "اضاءة", "انر", "ضع مشعل", "كشاف"},
                    "light_area", "{\"light\":\"torch\"}", "lighting intent"),
            new Rule(new String[]{"craft torch", "make torches", "اصنع مشاعل", "اصنع مشعل"},
                    "craft_item", "{\"output\":\"minecraft:torch\",\"count\":4}", "torch-crafting intent"),
            new Rule(new String[]{"deposit", "store items", "put in chest", "put items in chest", "اودع", "خزن", "ضع في الصندوق", "خزني"},
                    "deposit_item", "{}", "deposit intent"),
            new Rule(new String[]{"take from chest", "withdraw", "get from chest", "خذ من الصندوق", "اسحب", "اخرج من الصندوق"},
                    "take_from_container", "{}", "withdraw intent"),
            new Rule(new String[]{"find chest", "find a chest", "find container", "locate chest", "ابحث عن صندوق", "وين الصندوق"},
                    "find_nearby_container", "{\"radius\":8}", "container-search intent"),
            new Rule(new String[]{"smelt", "melt iron", "صهر", "اصهر", "صهر الحديد", "اذب", "أذب"},
                    "smelt_item", "{\"type\":\"smelting\",\"output\":\"\"}", "smelting intent"),
            new Rule(new String[]{"status", "how are you", "what are you doing", "your state", "حالتك", "كيف حالك", "ماذا تفعل", "شو عم تعمل", "وين وصلت"},
                    "inspect_ai_state", "{}", "status intent"),
            new Rule(new String[]{"inventory", "what do you have", "what is in your inventory", "ماذا تملك", "شنو عندك", "المخزون", "جرد"},
                    "inspect_companion_inventory", "{}", "inventory intent"),
            new Rule(new String[]{"what am i looking at", "what is this block", "inspect the block", "look at this block", "شنو هذا البلوك", "ما هذا البلوك", "ماذا ارى", "ماذا أرى"},
                    "inspect_look_block", "{\"distance\":6}", "look-block intent"),
            new Rule(new String[]{"where are we", "describe location", "where are you", "اين نحن", "وين احنا", "صف المكان", "أين نحن"},
                    "describe_location", "{}", "location intent"),
            new Rule(new String[]{"report", "daily report", "تقرير", "تقريرك اليومي"},
                    "daily_report", "{}", "report intent"),
            new Rule(new String[]{"nearby mobs", "nearby entities", "what is nearby", "look around", "المخلوقات القريبة", "من حولنا"},
                    "inspect_nearby_entities", "{\"radius\":16}", "nearby-entities intent"),
            new Rule(new String[]{"inspect chest", "what is in the chest", "inspect container", "افحص الصندوق", "ماذا في الصندوق"},
                    "inspect_container", "{}", "inspect-container intent"),
            new Rule(new String[]{"inspect equipment", "your armor", "your weapon", "معداتك", "سلاحك", "درعك"},
                    "inspect_equipment", "{\"slot\":\"all\"}", "equipment intent"),
            new Rule(new String[]{"permissions", "your permissions", "الصلاحيات", "الاذونات"},
                    "inspect_permissions", "{\"action\":\"status\"}", "permission-inspection intent"),
    };

    /**
     * Host mods may register local keyword intents after registering their {@link AITool}.
     * Registration is rejected when the tool is unknown or its fixed JSON arguments violate
     * the same runtime contract used for every executed tool.
     */
    public static void registerIntent(String[] keywords, String toolName, String jsonArguments, String reason) {
        if (keywords == null || keywords.length == 0) throw new IllegalArgumentException("At least one keyword is required");
        AITool tool = ToolRegistry.get(toolName);
        if (tool == null) throw new IllegalArgumentException("Unknown NPC Mind tool: " + toolName);
        String args = jsonArguments == null || jsonArguments.isBlank() ? "{}" : jsonArguments;
        ToolContract.Validation validation = ToolContract.validate(tool, args);
        if (!validation.ok()) throw new IllegalArgumentException("Invalid intent arguments for " + toolName + ": " + validation.reason());
        List<String> clean = new ArrayList<>();
        for (String keyword : keywords) {
            String normalized = normalize(keyword);
            if (!normalized.isBlank()) clean.add(normalized);
        }
        if (clean.isEmpty()) throw new IllegalArgumentException("No usable keywords provided");
        EXTENSIONS.add(0, new Rule(clean.toArray(String[]::new), toolName, args, reason == null ? "host-mod intent" : reason));
    }

    /** Strong match first, then parameterized movement/mining/search commands, then fixed aliases. */
    public static Route first(String raw) {
        Route route = resolve(raw);
        if (route == null) return null;
        AITool tool = ToolRegistry.get(route.tool());
        if (tool == null) return null;
        ToolContract.Validation validation = ToolContract.validate(tool, route.arguments());
        return validation.ok() ? route : null;
    }

    private static Route resolve(String raw) {
        String s = normalize(raw);
        if (s.isBlank() || s.length() > 300) return null;

        // Resource-specific intents run before the generic gather matcher. Wool is a mob drop,
        // so it must route through a real sheep hunt rather than block mining or inventory search.
        if (containsAny(s, "gather wool", "collect wool", "get wool", "need wool", "find wool", "اجمع الصوف", "جمع الصوف", "احصل على الصوف", "هات الصوف")) {
            int count = Math.max(1, Math.min(16, parsedCount(s, 3)));
            return new Route("combat_target", "{\"mode\":\"nearest_animal\",\"animal\":\"sheep\",\"count\":" + count + "}", "wool-from-sheep intent");
        }
        if (containsAny(s, "gather leather", "collect leather", "get leather", "اجمع الجلد", "احصل على الجلد", "هات الجلد")) {
            int count = Math.max(1, Math.min(16, parsedCount(s, 1)));
            return new Route("combat_target", "{\"mode\":\"nearest_animal\",\"animal\":\"cow\",\"count\":" + count + "}", "leather-from-cow intent");
        }
        if (containsAny(s, "gather coal", "collect coal", "get coal", "mine coal", "اجمع الفحم", "احصل على الفحم", "هات الفحم", "استخرج الفحم")) {
            int count = Math.max(1, Math.min(16, parsedCount(s, 4)));
            return new Route("gather_block", "{\"block\":\"coal_ore\",\"count\":" + count + "}", "coal-gather intent");
        }
        if (containsAny(s, "gather food", "collect meat", "get meat", "اجمع الطعام", "اجمع اللحم", "هات اللحم")) {
            int count = Math.max(1, Math.min(16, parsedCount(s, 2)));
            return new Route("combat_target", "{\"mode\":\"nearest_animal\",\"count\":" + count + "}", "food-from-animals intent");
        }

        // Named-target attacks resolve the requested entity family instead of defaulting every
        // "attack the zombie" request to the nearest hostile of any type.
        Matcher namedAttack = Pattern.compile("(?:attack|kill|fight|target|هاجم|اقتل|قاتل|استهدف)\\s+(?:(?:a|an|the|that|nearest|closest)\\s+)?(wither skeleton|iron golem|cave spider|zombie villager|zombie|husk|drowned|skeleton|stray|spider|creeper|enderman|witch|villager|sheep|cow|cows|pig|pigs|chicken|chickens|الزومبي|زومبي|هيكل عظمي|هيكل ويذر|عنكبوت الكهف|عنكبوت|كريبر|اندرمان|ساحرة|غول حديدي|قروي زومبي|قروي|خروف|الخروف|بقرة|البقرة|خنزير|الخنزير|دجاج|الدجاج)(?![\\p{L}\\p{N}_])").matcher(s);
        if (namedAttack.find()) {
            String targetName = namedAttack.group(1);
            String animal = switch (targetName) {
                case "sheep", "خروف", "الخروف" -> "sheep";
                case "cow", "cows", "بقرة", "البقرة" -> "cow";
                case "pig", "pigs", "خنزير", "الخنزير" -> "pig";
                case "chicken", "chickens", "دجاج", "الدجاج" -> "chicken";
                default -> null;
            };
            if (animal != null) {
                return new Route("combat_target", "{\"mode\":\"nearest_animal\",\"animal\":\"" + animal + "\",\"count\":1}", "named-animal hunt intent");
            }
            return new Route("combat_target", "{\"mode\":\"named\",\"target_name\":\"" + escapeJson(targetName) + "\"}", "named-entity combat intent");
        }

        // Parameterized hunt commands must be resolved before fixed aliases. This lets a request
        // such as "kill 3 sheep" continue through three real combat targets instead of stopping
        // after the first animal. Arabic numeral forms are accepted as well.
        Matcher huntCount = Pattern.compile("(?:kill|hunt|slaughter)\\s+(\\d{1,2})\\s+(sheep|cow|cows|pig|pigs|chicken|chickens)\\b").matcher(s);
        if (huntCount.find()) {
            // (Arabic counted hunts are handled by arHuntCount below.)
            String species = switch (huntCount.group(2)) {
                case "sheep" -> "sheep";
                case "cow", "cows" -> "cow";
                case "pig", "pigs" -> "pig";
                default -> "chicken";
            };
            int count = Math.max(1, Math.min(16, Integer.parseInt(huntCount.group(1))));
            return new Route("combat_target", "{\"mode\":\"nearest_animal\",\"animal\":\"" + species + "\",\"count\":" + count + "}", "counted-animal-hunt intent");
        }

        // Arabic counted hunts: "اقتل 3 خراف" / "اصطد 5 من الدجاج". Digits are already
        // normalised from Arabic-Indic forms by normalize(), so only number words are missing.
        Matcher arHuntCount = Pattern.compile("(?:اقتل|اصطد|اذبح)\\s+(?:(?:من|من ال)\\s*)?(\\d{1,2})\\s*(?:من\\s+)?(الخراف|خراف|خرافا|البقر|بقر|الابقار|ابقار|الخنازير|خنازير|الدجاج|دجاج|الخروف|خروف|البقرة|بقرة|الخنزير|خنزير)(?![\\p{L}\\p{N}_])").matcher(s);
        if (arHuntCount.find()) {
            String species = switch (arHuntCount.group(2)) {
                case "الخراف", "خراف", "خرافا", "الخروف", "خروف" -> "sheep";
                case "البقر", "بقر", "الابقار", "ابقار", "البقرة", "بقرة" -> "cow";
                case "الخنازير", "خنازير", "الخنزير", "خنزير" -> "pig";
                default -> "chicken";
            };
            int count = Math.max(1, Math.min(16, Integer.parseInt(arHuntCount.group(1))));
            return new Route("combat_target", "{\"mode\":\"nearest_animal\",\"animal\":\"" + species + "\",\"count\":" + count + "}", "arabic counted-animal-hunt intent");
        }

        Route teaching = teachingRoute(s);
        if (teaching != null) return teaching;

        Route coordinates = coordinateRoute(s, raw);
        if (coordinates != null) return coordinates;

        for (Rule r : EXTENSIONS) {
            Route result = matchRule(s, r);
            if (result != null) return result;
        }

        // Direct local capability routes. These must precede broad keyword rules so "combat
        // status" doesn't become an attack, "my inventory" doesn't inspect the NPC, and "place
        // stone" doesn't become mining just because the material name is also an ore keyword.
        Route utility = utilityRoute(s, raw);
        if (utility != null) return utility;

        // These tools require user-supplied payloads. A fixed empty-object rule used to
        // select them and then fail ToolContract before execute() could ask for the value.
        Route memory = rememberRoute(s);
        if (memory != null) return memory;

        // Explicit high-level intents must win over materials mentioned as objects. Without
        // this priority, "attack an iron golem" became an iron-ore search and "smelt iron"
        // became mining simply because the word "iron" was present.
        Route structure = structureRoute(s);
        if (structure != null) return structure;

        if (!containsAny(s, "blueprint", "مخطط") && containsAny(s, "build", "construct", "create a wall", "create wall", "create a house", "create house", "create a tower", "create tower", "make a wall", "make a house", "make house", "make a tower", "make tower", "make a bridge", "make bridge", "make a floor", "make floor", "ابن", "ابني", "بناء", "انشئ", "أنشئ")) {
            String pattern = containsAny(s, "house", "بيت", "منزل") ? "house"
                    : containsAny(s, "tower", "برج") ? "tower"
                    : containsAny(s, "bridge", "جسر") ? "bridge"
                    : containsAny(s, "floor", "ارضية", "أرضية") ? "floor"
                    : containsAny(s, "line", "خط") ? "line" : "wall";
            StringBuilder args = new StringBuilder("{\"pattern\":\"").append(pattern).append('\"');
            String material = buildingBlockId(s);
            if (material != null) args.append(",\"block\":\"").append(escapeJson(material)).append('\"');
            Integer[] xyz = explicitCoordinates(s);
            if (xyz != null) args.append(",\"x\":").append(xyz[0]).append(",\"y\":").append(xyz[1]).append(",\"z\":").append(xyz[2]);
            Integer width = namedDimension(s, "width", "wide", "عرض");
            Integer height = namedDimension(s, "height", "high", "tall", "ارتفاع");
            Integer length = namedDimension(s, "length", "long", "طول");
            if (width != null) args.append(",\"width\":").append(width);
            if (height != null) args.append(",\"height\":").append(height);
            if (length != null) args.append(",\"length\":").append(length);
            args.append('}');
            return new Route("build_pattern", args.toString(), pattern + "-build intent");
        }


        if (containsAny(s, "smelt", "melt", "صهر", "اصهر", "أصهر", "اذب", "أذب")) {
            String output = containsAny(s, "iron", "حديد") ? "iron"
                    : containsAny(s, "gold", "ذهب") ? "gold"
                    : containsAny(s, "glass", "زجاج") ? "glass"
                    : containsAny(s, "charcoal", "فحم نباتي") ? "charcoal" : "";
            String type = containsAny(s, "smoker", "smoke", "دخن") ? "smoking"
                    : containsAny(s, "blast furnace", "blasting", "فرن صهر") ? "blasting" : "smelting";
            return new Route("smelt_item", "{\"type\":\"" + type + "\",\"output\":\"" + output + "\"}", "smelting intent");
        }

        Route crafting = craftingRoute(s);
        if (crafting != null) return crafting;

        // A coordinate-targeted mine command is an action on a specific cell, not a vein search.
        boolean directMiningIntent = containsWord(s, "mine") || containsAny(s, "dig", "break", "excavate", "اكسر", "احفر", "نقب", "استخرج", "عدن");
        Integer[] directMiningCoords = explicitCoordinates(s);
        if (directMiningIntent && directMiningCoords != null) {
            return new Route("mine_block", "{\"x\":" + directMiningCoords[0] + ",\"y\":" + directMiningCoords[1] + ",\"z\":" + directMiningCoords[2] + "}", "coordinate-mining intent");
        }

        String blockId = blockTarget(s);
        boolean mineVerb = containsWord(s, "mine") || containsAny(s, "dig", "break", "harvest ore", "extract", "اكسر", "كسر", "احفر", "احفري", "استخرج", "نقب", "عدن", "احصل على");
        boolean searchVerb = containsAny(s, "find", "search", "look for", "locate", "where is", "ابحث", "دور على", "اعثر على", "وين", "اين");
        boolean gatherVerb = containsAny(s, "gather", "collect", "get me", "get ", "احضر", "اجمع", "جمع", "اجلب");
        if (blockId != null && (mineVerb || searchVerb || gatherVerb)) {
            int count = parsedCount(s, 4);
            if (isOreId(blockId)) {
                boolean collect = mineVerb || gatherVerb;
                return new Route("smart_vein_mining", "{\"target\":\"" + blockId + "\",\"collect\":" + collect + ",\"radius\":12}",
                        collect ? "ore-mining intent" : "ore-search intent");
            }
            if (searchVerb && !mineVerb && !gatherVerb) {
                return new Route("smart_vein_mining", "{\"target\":\"" + blockId + "\",\"collect\":false,\"radius\":12}", "block-search intent");
            }
            String keyword = blockKeyword(blockId);
            return new Route("gather_block", "{\"block\":\"" + keyword + "\",\"count\":" + count + "}", "block-gather intent");
        }

        if (containsWord(s, "mine") || containsAny(s, "dig", "break this", "break that", "mine this", "اكسر هذا", "احفر", "احفري", "اكسر", "كسر")) {
            return new Route("mine_block", "{}", "looked-at-block mining intent");
        }

        if (gatherVerb) {
            int count = parsedCount(s, 8);
            if (containsAny(s, "wood", "log", "tree", "خشب", "شجر", "جذوع"))  // substring match is fine here: gather already requires a gather verb
                return new Route("gather_resources", "{\"resource\":\"wood\",\"count\":" + count + "}", "quantity-aware wood intent");
            if (containsAny(s, "stone", "dirt", "sand", "gravel", "cobblestone", "حجر", "تراب", "رمل")) {
                String kw = blockKeyword(blockId == null ? (containsAny(s,"sand","رمل") ? "minecraft:sand" : containsAny(s,"dirt","تراب") ? "minecraft:dirt" : "minecraft:stone") : blockId);
                return new Route("gather_block", "{\"block\":\"" + kw + "\",\"count\":" + count + "}", "quantity-aware resource intent");
            }
            return new Route("gather_resources", "{\"count\":" + count + "}", "quantity-aware resource intent");
        }

        for (Rule r : RULES) {
            Route result = matchRule(s, r);
            if (result != null) return result;
        }

        return null;
    }

    private static Route teachingRoute(String s) {
        if (!containsAny(s, "teach trigger", "teach the bot", "when i say", "علّم البوت", "علم البوت")) return null;
        String phrase = "", instruction = "";
        int arrow = s.indexOf("=>");
        if (arrow >= 0) {
            String left = s.substring(0, arrow).trim();
            int colon = left.indexOf(':');
            phrase = (colon >= 0 ? left.substring(colon + 1) : left.replaceFirst("^(?:teach trigger|teach the bot)\\s*", "")).trim();
            instruction = s.substring(arrow + 2).trim();
        } else {
            Matcher m = Pattern.compile("when i say\\s+(.+?)\\s+(?:then|do|run)\\s+(.+)$").matcher(s);
            if (m.find()) { phrase = m.group(1).trim(); instruction = m.group(2).trim(); }
            else {
                Matcher ar = Pattern.compile("(?:علّم البوت|علم البوت)\\s+(?:عندما اقول|اذا قلت)\\s+(.+?)\\s+(?:نفذ|افعل)\\s+(.+)$").matcher(s);
                if (ar.find()) { phrase = ar.group(1).trim(); instruction = ar.group(2).trim(); }
            }
        }
        if (phrase.isBlank() || instruction.isBlank()) return null;
        return new Route("teach_trigger", "{\"phrase\":\"" + escapeJson(phrase) + "\",\"instruction\":\"" + escapeJson(instruction) + "\"}", "teach-trigger intent");
    }

    private static Route utilityRoute(String s, String raw) {
        if (containsAny(s, "combat status", "combat state", "are you fighting", "who are you fighting", "حالة القتال", "من تقاتل", "من تقاتل الآن"))
            return new Route("combat_status", "{}", "combat-status intent");
        if (containsAny(s, "task status", "current task", "task queue", "what task are you doing", "حالة المهمة", "المهمة الحالية", "طابور المهام"))
            return new Route("task_status", "{}", "task-status intent");
        if (containsAny(s, "my inventory", "check my inventory", "player inventory", "my items", "مخزوني", "مخزون اللاعب", "اغراضي", "أغراضي"))
            return new Route("inspect_inventory", "{}", "owner-inventory intent");
        if (containsAny(s, "inspect companion inventory", "your inventory", "your items", "inventory contents", "مخزونك", "مخزونك انت"))
            return new Route("inspect_companion_inventory", "{}", "companion-inventory intent");
        if (containsAny(s, "world memory", "inspect memory", "show memory", "what have you observed", "ذكريات العالم", "ذاكرة العالم", "ماذا تتذكر عن العالم"))
            return new Route("inspect_world_memory", "{}", "world-memory intent");
        if (containsAny(s, "inspect recipes", "show recipes", "list recipes", "recipe list", "ما هي الوصفات", "اعرض الوصفات", "قائمة الوصفات", "وصفات التصنيع")) {
            String type = containsAny(s, "smelting", "smelt", "صهر") ? "smelting"
                    : containsAny(s, "blasting", "blast furnace", "فرن صهر") ? "blasting"
                    : containsAny(s, "smoking", "smoker", "تدخين") ? "smoking"
                    : containsAny(s, "crafting", "crafting table", "تصنيع") ? "crafting" : "all";
            String output = recipeOutput(s);
            return new Route("inspect_recipes", "{\"type\":\"" + type + "\"" + (output == null ? "" : ",\"output\":\"" + escapeJson(output) + "\"") + ",\"limit\":32}", "recipe-inspection intent");
        }
        if (containsAny(s, "inspect registry", "search registry", "registry search", "item id", "block id", "entity id", "معرف العنصر", "معرف البلوك", "سجل العناصر")) {
            String kind = containsAny(s, "block", "blocks", "بلوك", "كتلة") ? "block"
                    : containsAny(s, "entity", "mob", "كيان", "مخلوق") ? "entity"
                    : containsAny(s, "fluid", "liquid", "سائل") ? "fluid" : "item";
            String query = registryQuery(s);
            return new Route("inspect_registry", "{\"kind\":\"" + kind + "\",\"query\":\"" + escapeJson(query) + "\"}", "registry-search intent");
        }
        if (containsAny(s, "forge energy", "energy storage", "energy level", "fe storage", "طاقة فورج", "مخزون الطاقة", "مستوى الطاقة"))
            return new Route("inspect_forge_energy", "{\"target\":\"hand\",\"distance\":6}", "forge-energy intent");
        if (containsAny(s, "build blueprint", "build this blueprint", "blueprint layout", "ابن المخطط", "بناء مخطط", "ابني مخطط")) {
            String layout = null;
            for (String marker : new String[]{"layout ", "pattern ", "مخطط ", "التصميم "}) {
                int at = s.indexOf(marker);
                if (at >= 0) { layout = s.substring(at + marker.length()).trim(); break; }
            }
            String block = buildingBlockId(s);
            if (layout == null || layout.isBlank()) return null;
            for (String terminator : new String[]{" with ", " using ", " made of ", " from ", " at ", " coordinates ", " من ", " باستخدام ", " عند "}) {
                int end = layout.indexOf(terminator);
                if (end >= 0) layout = layout.substring(0, end).trim();
            }
            layout = layout.replace('\\', '/').replace(',', '/').replaceAll("\\s+", "");
            layout = layout.replaceAll("[^#./]", "");
            if (layout.isBlank() || !layout.contains("#")) return null;
            if (block == null) block = placementBlockId(s);
            if (block == null) return null;
            Integer[] bp = explicitCoordinates(s);
            String args = "{\"block\":\"" + escapeJson(block) + "\",\"layout\":\"" + escapeJson(layout) + "\"";
            if (bp != null) args += ",\"x\":" + bp[0] + ",\"y\":" + bp[1] + ",\"z\":" + bp[2];
            return new Route("build_blueprint", args + "}", "blueprint-build intent");
        }
        if (containsAny(s, "base maintenance", "inspect maintenance", "maintenance scan", "صيانة القاعدة", "افحص الصيانة"))
            return new Route("inspect_base_maintenance", "{\"radius\":12}", "base-maintenance intent");
        if (containsAny(s, "evaluate base site", "find a base site", "find a place for a base", "where should we build our base", "افحص موقع القاعدة", "ابحث عن موقع للقاعدة", "مكان مناسب للقاعدة"))
            return new Route("evaluate_base_site", "{\"radius\":12}", "base-site-evaluation intent");
        if (containsAny(s, "inspect villager trades", "villager trades", "villager offers", "what can the villager trade", "عروض القروي", "تجارة القروي"))
            return new Route("inspect_villager_trades", "{\"radius\":16}", "villager-trade-inspection intent");
        if (containsAny(s, "trade with villager", "accept villager offer", "نفذ صفقة مع القروي", "تبادل مع القروي")) {
            Integer offer = explicitIntegerAfter(s, "offer", "index", "عرض", "رقم");
            if (offer == null) return null;
            return new Route("trade_with_villager", "{\"offer\":" + Math.max(0, Math.min(128, offer)) + "}", "villager-trade intent");
        }
        if (containsAny(s, "inspect resource ledger", "resource ledger", "reserved resources", "available resources", "سجل الموارد", "الموارد المحجوزة"))
            return new Route("inspect_resource_ledger", "{}", "resource-ledger intent");
        if (containsAny(s, "reserve resource", "reserve materials", "reserve stock", "reserve ", "احجز موارد", "احجز مواد", "احجز ")) {
            String item = resourceItemId(s);
            Integer count = namedDimension(s, "count", "amount", "quantity", "عدد");
            if (count == null) count = parsedCount(s, 1);
            if (item == null) return null;
            return new Route("reserve_resource", "{\"item\":\"" + item + "\",\"count\":" + Math.max(1, Math.min(64, count)) + ",\"action\":\"reserve\"}", "reserve-resource intent");
        }
        if (containsAny(s, "release resource reservation", "release reservation", "free reserved resources", "release ", "الغاء حجز الموارد", "حرر الموارد", "حرر ")) {
            String item = resourceItemId(s);
            Integer count = namedDimension(s, "count", "amount", "quantity", "عدد");
            if (count == null) count = parsedCount(s, 1);
            if (item == null) return null;
            return new Route("reserve_resource", "{\"item\":\"" + item + "\",\"count\":" + Math.max(1, Math.min(64, count)) + ",\"action\":\"release\"}", "release-resource intent");
        }
        if (containsAny(s, "give me", "give the owner", "hand me", "اعطني", "أعطني", "اعطيني", "ناولني")) {
            String item = resourceItemId(s);
            Integer count = parsedCount(s, 1);
            if (item == null) return null;
            return new Route("give_item_to_owner", "{\"item_id\":\"" + item + "\",\"count\":" + Math.max(1, Math.min(64, count)) + "}", "give-item intent");
        }
        if (isEatIntent(s))
            return new Route("manage_autonomy", "{\"action\":\"status\",\"eat\":true}", "eat-from-inventory intent");
        if (containsAny(s, "prepare loadout", "equip best gear", "equip your gear", "equip gear", "equip your equipment", "جهز معداتك", "ارتد افضل عتاد", "ارتد أفضل عتاد"))
            return new Route("prepare_loadout", "{\"action\":\"equip\"}", "loadout-prepare intent");
        if (containsAny(s, "inspect loadout", "show loadout", "loadout status", "جهز تقرير المعدات", "اعرض العتاد"))
            return new Route("prepare_loadout", "{\"action\":\"inspect\"}", "loadout-inspection intent");
        if (containsAny(s, "team status", "inspect team", "show team", "حالة الفريق", "اعرض الفريق"))
            return new Route("manage_companion_team", "{}", "team-status intent");
        if (containsAny(s, "set team", "join team", "assign team", "انضم للفريق", "انضم الى الفريق", "عيّن الفريق")) {
            Matcher team = Pattern.compile("(?:team|فريق)\\s+([a-z0-9_-]+)(?:\\s+(?:as|role|دور)\\s+([a-z0-9_-]+))?").matcher(s);
            if (team.find()) {
                String id = team.group(1);
                String role = team.group(2) == null ? "member" : team.group(2);
                return new Route("manage_companion_team", "{\"team_id\":\"" + escapeJson(id) + "\",\"role\":\"" + escapeJson(role) + "\"}", "team-assignment intent");
            }
            return new Route("manage_companion_team", "{}", "team-status intent (team id not supplied)");
        }
        if (containsAny(s, "survival mode", "survival progression", "وضع البقاء", "نمط البقاء"))
            return new Route("manage_autonomy", "{\"action\":\"survival\"}", "survival-progression mode intent");
        if (containsAny(s, "autonomy status", "autonomy", "autonomous routine", "autonomous mode", "الاستقلالية", "نظام الاستقلالية")) {
            if (containsAny(s, "disable", "stop", "turn off", "تعطيل", "اوقف", "أوقف"))
                return new Route("manage_autonomy", "{\"action\":\"disable\"}", "autonomy-disable intent");
            if (isEatIntent(s))
                return new Route("manage_autonomy", "{\"action\":\"status\",\"eat\":true}", "eat-from-inventory intent");
            String mode = containsAny(s, "follow", "اتبع") ? "FOLLOW"
                    : containsAny(s, "bodyguard", "guard", "حارس") ? "BODYGUARD"
                    : containsAny(s, "explore", "explorer", "استكشف") ? "EXPLORER"
                    : containsAny(s, "engineer", "مهندس") ? "ENGINEER"
                    : containsAny(s, "miner", "mining mode", "منقب", "التعدين") ? "MINER"
                    : containsAny(s, "farmer", "farming mode", "مزارع", "الزراعة") ? "FARMER"
                    : containsAny(s, "survival mode", "survival progression", "survive", "وضع البقاء", "نمط البقاء", "عيش في العالم") ? "SURVIVAL" : null;
            if (mode != null && containsAny(s, "mode", "set", "switch", "وضع", "نمط"))
                return new Route("manage_autonomy", "{\"action\":\"mode\",\"value\":\"" + mode + "\"}", "autonomy-mode intent");
            if (containsAny(s, "enable", "start", "activate", "تشغيل", "فعل", "فعّل"))
                return new Route("manage_autonomy", "{\"action\":\"enable\"}", "autonomy-enable intent");
            return new Route("manage_autonomy", "{\"action\":\"status\"}", "autonomy-status intent");
        }
        if (containsAny(s, "goal status", "project status", "current goal", "حالة الهدف", "حالة المشروع"))
            return new Route("manage_long_term_goal", "{\"action\":\"status\"}", "goal-status intent");
        if (containsAny(s, "cancel goal", "cancel project", "stop the project", "الغ الهدف", "الغاء الهدف", "ألغ الهدف", "الغ المشروع"))
            return new Route("manage_long_term_goal", "{\"action\":\"cancel\"}", "goal-cancel intent");
        if (containsAny(s, "complete goal", "complete project", "finish the project", "اكمل الهدف", "انهاء الهدف", "أنهِ الهدف", "أكمل المشروع"))
            return new Route("manage_long_term_goal", "{\"action\":\"complete\"}", "goal-complete intent");
        if (containsAny(s, "start a goal", "start project", "create project", "new project", "ابدأ مشروع", "ابدا مشروع", "ضع هدف", "انشئ هدف")) {
            String title = goalTitle(s);
            if (title.isBlank()) return new Route("manage_long_term_goal", "{\"action\":\"status\"}", "goal-status intent");
            return new Route("manage_long_term_goal", "{\"action\":\"start\",\"title\":\"" + escapeJson(title) + "\",\"plan\":\"" + escapeJson(title) + "\"}", "goal-start intent");
        }

        // Placement is a distinct operation from mining/search. The inventory matcher later
        // compares registry path tokens, so "place stone" cannot select redstone by substring.
        if (isPlacementIntent(s) && !containsAny(s, "place torches", "put down torches", "light up", "اضاءة المنطقة", "انر المنطقة")) {
            String block = placementBlockId(s);
            Integer count = parsedCount(s, 1);
            StringBuilder args = new StringBuilder("{\"count\":" + count + (block == null ? "" : ",\"block\":\"" + escapeJson(block) + "\"") );
            Integer[] coords = explicitCoordinates(s);
            if (coords != null) args.append(",\"x\":").append(coords[0]).append(",\"y\":").append(coords[1]).append(",\"z\":").append(coords[2]);
            args.append('}');
            return new Route("place_block", args.toString(), "place-block intent");
        }
        return null;
    }

    private static String recipeOutput(String s) {
        if (containsAny(s, "iron ingot", "ingot iron", "سبيكة الحديد")) return "iron_ingot";
        if (containsAny(s, "gold ingot", "سبيكة الذهب")) return "gold_ingot";
        if (containsAny(s, "torch", "مشعل")) return "torch";
        if (containsAny(s, "bread", "خبز")) return "bread";
        if (containsAny(s, "diamond", "الماس", "الألماس")) return "diamond";
        if (containsAny(s, "stone", "حجر")) return "stone";
        return null;
    }

    private static String registryQuery(String s) {
        String[] markers = {"registry for ", "item id ", "block id ", "entity id ", "search registry for ", "معرف العنصر ", "معرف البلوك "};
        for (String marker : markers) {
            int at = s.indexOf(marker);
            if (at >= 0) {
                String q = s.substring(at + marker.length()).trim();
                if (!q.isBlank()) return q.length() > 80 ? q.substring(0, 80) : q;
            }
        }
        String[] parts = s.split("\\s+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private static String resourceItemId(String s) {
        if (containsAny(s, "iron ingot", "ingot of iron", "سبيكة الحديد")) return "minecraft:iron_ingot";
        if (containsAny(s, "gold ingot", "ingot of gold", "سبيكة الذهب")) return "minecraft:gold_ingot";
        if (containsAny(s, "iron ore", "خام الحديد")) return "minecraft:iron_ore";
        if (containsAny(s, "gold ore", "خام الذهب")) return "minecraft:gold_ore";
        if (containsAny(s, "diamond", "الماس", "الألماس")) return "minecraft:diamond";
        if (containsAny(s, "coal", "فحم")) return "minecraft:coal";
        if (containsAny(s, "redstone", "ريدستون")) return "minecraft:redstone";
        if (containsAny(s, "lapis", "لازورد")) return "minecraft:lapis_lazuli";
        if (containsAny(s, "emerald", "زمرد")) return "minecraft:emerald";
        if (containsAny(s, "oak log", "سجل بلوط") || containsWord(s, "logs") || containsWord(s, "log") || containsWord(s, "wood") || containsAny(s, "خشب", "جذوع", "شجر")) return "minecraft:oak_log";
        if (containsAny(s, "planks", "ألواح", "الواح")) return "minecraft:oak_planks";
        if (containsAny(s, "cobblestone", "كوبلستون")) return "minecraft:cobblestone";
        if (containsAny(s, "stone", "حجر")) return "minecraft:stone";
        if (containsAny(s, "dirt", "تراب")) return "minecraft:dirt";
        if (containsAny(s, "sand", "رمل")) return "minecraft:sand";
        if (containsAny(s, "wheat", "قمح")) return "minecraft:wheat";
        if (containsAny(s, "bread", "خبز")) return "minecraft:bread";
        if (containsAny(s, "torch", "مشعل")) return "minecraft:torch";
        return null;
    }

    private static String placementBlockId(String s) {
        if (containsAny(s, "iron block", "block of iron", "كتلة الحديد")) return "minecraft:iron_block";
        if (containsAny(s, "gold block", "block of gold", "كتلة الذهب")) return "minecraft:gold_block";
        if (containsAny(s, "diamond block", "block of diamond", "كتلة الماس")) return "minecraft:diamond_block";
        if (containsAny(s, "emerald block", "block of emerald", "كتلة الزمرد")) return "minecraft:emerald_block";
        if (containsAny(s, "redstone block", "كتلة ريدستون")) return "minecraft:redstone_block";
        if (containsAny(s, "stone bricks", "stone_bricks", "طوب حجري")) return "minecraft:stone_bricks";
        if (containsAny(s, "oak planks")) return "minecraft:oak_planks";
        if (containsAny(s, "wooden planks", "planks", "ألواح خشبية", "الواح خشبية")) return "minecraft:planks";
        if (containsAny(s, "oak log")) return "minecraft:oak_log";
        if (containsAny(s, "wood log", "جذوع") || containsWord(s, "logs") || containsWord(s, "log")) return "log";
        if (containsAny(s, "خشب") || containsWord(s, "wood")) return "wood";
        if (containsAny(s, "cobblestone", "كوبلستون")) return "minecraft:cobblestone";
        if (containsAny(s, "torch", "مشعل")) return "minecraft:torch";
        if (containsAny(s, "chest", "صندوق")) return "minecraft:chest";
        if (containsAny(s, "glass", "زجاج")) return "minecraft:glass";
        if (containsAny(s, "sand", "رمل")) return "minecraft:sand";
        if (containsAny(s, "dirt", "تراب")) return "minecraft:dirt";
        if (containsAny(s, "stone", "حجر")) return "minecraft:stone";
        if (containsAny(s, "iron", "حديد")) return "minecraft:iron_block";
        if (containsAny(s, "gold", "ذهب")) return "minecraft:gold_block";
        if (containsAny(s, "diamond", "الماس", "الألماس")) return "minecraft:diamond_block";
        if (containsAny(s, "coal", "فحم")) return "minecraft:coal_block";
        return null;
    }

    private static String buildingBlockId(String s) {
        if (!containsAny(s, " with ", " using ", " made of ", " from ", "بـ", "باستخدام", "من حجر", "من خشب")) return null;
        if (containsAny(s, "stone bricks", "stone_bricks", "طوب حجري")) return "minecraft:stone_bricks";
        if (containsAny(s, "cobblestone", "كوبلستون")) return "minecraft:cobblestone";
        if (containsAny(s, "oak planks")) return "minecraft:oak_planks";
        if (containsAny(s, "wooden planks", "planks", "ألواح خشبية", "الواح خشبية")) return "minecraft:planks";
        if (containsAny(s, "sandstone", "حجر رملي")) return "minecraft:sandstone";
        if (containsAny(s, "stone", "حجر")) return "minecraft:stone";
        if (containsAny(s, "dirt", "تراب")) return "minecraft:dirt";
        if (containsAny(s, "sand", "رمل")) return "minecraft:sand";
        if (containsAny(s, "oak log")) return "minecraft:oak_log";
        if (containsAny(s, "oak log", "سجل بلوط") || containsWord(s, "logs") || containsWord(s, "log")) return "log";
        if (containsAny(s, "خشب") || containsWord(s, "wood")) return "wood";
        return null;
    }

    private static boolean isPlacementIntent(String s) {
        return containsAny(s, "place", "put down", "set down", "lay down", "place a block", "put a block", "ضع", "حط", "ارصف", "ثبت", "ثبت بلوك");
    }

    private static Integer[] explicitCoordinates(String s) {
        if (s == null || !containsAny(s, " at ", " coordinates ", " coords ", "position ", " عند ", "بالإحداثيات", "بالاحداثيات", "بموقع", "في موقع")) return null;
        Matcher m = COORDINATES.matcher(s);
        while (m.find()) {
            try {
                int x=Integer.parseInt(m.group(1)), y=Integer.parseInt(m.group(2)), z=Integer.parseInt(m.group(3));
                if (y >= -64 && y <= 320 && x >= -30_000_000 && x <= 30_000_000 && z >= -30_000_000 && z <= 30_000_000)
                    return new Integer[]{x,y,z};
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static Integer namedDimension(String s, String... names) {
        for (String name : names) {
            String normalizedName = normalize(name);
            String marker = "(?:" + Pattern.quote(normalizedName) + "|[بولو]" + Pattern.quote(normalizedName) + ")";
            Pattern p = Pattern.compile("(?:^|\\s)" + marker + "(?:\\s*(?:=|is|to|ب)?\\s*|\\s+)(\\d{1,3})(?![\\w])");
            Matcher m=p.matcher(s);
            if(m.find()) try { return Math.max(1, Math.min(64, Integer.parseInt(m.group(1)))); } catch(NumberFormatException ignored){}
        }
        return null;
    }

    private static Integer explicitIntegerAfter(String s, String... markers) {
        for (String marker : markers) {
            Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(normalize(marker)) + "\\s*(\\d{1,3})(?![\\w])").matcher(s);
            if (m.find()) try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static String goalTitle(String s) {
        String title = s;
        for (String prefix : new String[]{"start a goal", "start project", "create project", "new project", "ابدأ مشروع", "ابدا مشروع", "ضع هدف", "انشئ هدف"}) {
            String n=normalize(prefix);
            if (title.startsWith(n)) { title=title.substring(n.length()).trim(); break; }
        }
        title = title.replaceFirst("^(?:to|for|about|that|من اجل|لـ|ل)\\s+", "").trim();
        if (title.length() > 100) title=title.substring(0,100).trim();
        return title;
    }

    private static Route rememberRoute(String s) {
        String[] prefixes = {"please remember that", "please remember", "could you remember that", "could you remember",
                "can you remember that", "can you remember", "i want you to remember that", "i want you to remember",
                "remember that", "remember", "تذكر أن", "تذكر ان", "تذكر"};
        String matched = null;
        for (String prefix : prefixes) {
            String n = normalize(prefix);
            if (s.equals(n) || s.startsWith(n + " ")) {
                if (matched == null || n.length() > matched.length()) matched = n;
            }
        }
        // Questions like "do you remember where the village is?" are not memory-write commands.
        if (matched == null || s.startsWith("do you remember ") || s.startsWith("did you remember ")) return null;
        String fact = s.substring(matched.length()).trim();
        fact = fact.replaceFirst("^(?:that|ان|انني|اني|بأن|بان|أن)\\s+", "").trim();
        if (fact.isBlank()) return null;
        if (fact.length() > 220) fact = fact.substring(0, 220).trim();
        return new Route("remember", "{\"fact\":\"" + escapeJson(fact) + "\"}", "explicit-memory intent");
    }

    private static Route craftingRoute(String s) {
        String[] verbs = {"please craft", "craft", "please make", "make me", "make", "create", "اصنع لي", "اصنع", "صنع لي", "صنع", "صنعي", "اعمل لي", "اعمل", "سوي لي", "سوي"};
        int best = -1;
        String matched = null;
        for (String verb : verbs) {
            String normalizedVerb = normalize(verb);
            int at = findPhrase(s, normalizedVerb);
            if (at >= 0 && (best < 0 || at < best || (at == best && normalizedVerb.length() > matched.length()))) {
                best = at;
                matched = normalizedVerb;
            }
        }
        if (best < 0) return null;
        // Avoid hijacking plain discussion such as "what can you make" with no payload.
        String item = s.substring(best + matched.length()).trim();
        item = item.replaceFirst("^(?:me|for me|a|an|the|some|لي|ليّ)\\s+", "").trim();
        int count = parsedCount(item, 1);
        item = item.replaceFirst("^\\d{1,3}\\s+", "").trim();
        item = item.replaceFirst("\\s+(?:x\\s*)?\\d{1,3}$", "").trim();
        item = item.replaceFirst("\\s+(?:for me|please)$", "").trim();
        if (item.isBlank() || item.equals("item") || item.equals("items") || item.equals("something") || item.equals("what")) return null;
        String itemAlias = normalize(item);
        if (itemAlias.equals("torch") || itemAlias.equals("torches") || itemAlias.equals("مشعل") || itemAlias.equals("مشاعل")) item = "minecraft:torch";
        else if (itemAlias.equals("stick") || itemAlias.equals("sticks") || itemAlias.equals("عصا") || itemAlias.equals("عصي")) item = "minecraft:stick";
        else if (itemAlias.equals("crafting table") || itemAlias.equals("طاولة تصنيع") || itemAlias.equals("طاولة الحرف")) item = "minecraft:crafting_table";
        else if (itemAlias.equals("chest") || itemAlias.equals("chests") || itemAlias.equals("صندوق") || itemAlias.equals("الصندوق")) item = "minecraft:chest";
        else if (itemAlias.equals("bread") || itemAlias.equals("خبز")) item = "minecraft:bread";
        else if (itemAlias.equals("furnace") || itemAlias.equals("فرن")) item = "minecraft:furnace";
        return new Route("craft_item", "{\"output\":\"" + escapeJson(item) + "\",\"count\":" + Math.max(1, Math.min(64, count)) + "}", "explicit-crafting intent");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    private static Route coordinateRoute(String normalized, String raw) {
        boolean movementIntent = containsAny(normalized, "go to", "move to", "walk to", "navigate to", "travel to", "coordinates", "goto", "اذهب الى", "اذهب الي", "تحرك الى", "تحرك الي", "امش الى", "روح الى", "انتقل الى");
        boolean commaCoordinates = raw != null && raw.matches("(?s)^\\s*[-+]?\\d{1,8}\\s*,\\s*[-+]?\\d{1,3}\\s*,\\s*[-+]?\\d{1,8}\\s*$");
        if (!movementIntent && !commaCoordinates) return null;
        Matcher m = COORDINATES.matcher(normalized);
        if (!m.find()) return null;
        try {
            int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2)), z = Integer.parseInt(m.group(3));
            if (y < -64 || y > 320 || x < -30_000_000 || x > 30_000_000 || z < -30_000_000 || z > 30_000_000) return null;
            return new Route("move_to", "{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}", "coordinate-navigation intent");
        } catch (NumberFormatException ignored) { return null; }
    }

    private static Route structureRoute(String s) {
        String id = containsAny(s, "village", "قرية") ? "village"
                : containsAny(s, "stronghold", "معقل", "حصن النهاية") ? "stronghold"
                : containsAny(s, "mineshaft", "abandoned mine", "منجم مهجور") ? "mineshaft"
                : containsAny(s, "nether fortress", "fortress", "قلعة النذر", "حصن النذر") ? "fortress"
                : containsAny(s, "bastion", "باستيون") ? "bastion"
                : containsAny(s, "ancient city", "المدينة القديمة") ? "ancient_city"
                : containsAny(s, "end city", "مدينة النهاية") ? "end_city"
                : containsAny(s, "mansion", "القصر") ? "mansion"
                : containsAny(s, "monument", "النصب البحري") ? "monument"
                : containsAny(s, "shipwreck", "حطام سفينة", "سفينة غارقة") ? "shipwreck"
                : containsAny(s, "desert temple", "desert pyramid", "معبد الصحراء", "هرم الصحراء") ? "desert_pyramid"
                : containsAny(s, "jungle temple", "معبد الغابة", "معبد الأدغال") ? "jungle_temple" : null;
        if (id == null) return null;
        if (!containsAny(s, "find", "search", "locate", "look for", "ابحث", "دور", "اعثر", "وين", "اين") && !s.equals(id)) return null;
        return new Route("search_structure", "{\"structure\":\"" + id + "\",\"radius\":128}", "structure-search intent");
    }

    private static String blockTarget(String s) {
        // Longest/most specific synonyms must precede their shorter forms.
        if (containsAny(s, "ancient debris", "الحطام القديم")) return "minecraft:ancient_debris";
        if (containsAny(s, "diamond ore", "diamond_ore", "diamond", "الماس", "الألماس")) return "minecraft:diamond_ore";
        if (containsAny(s, "iron ore", "iron_ore", "خام الحديد", "الحديد", "iron")) return "minecraft:iron_ore";
        if (containsAny(s, "gold ore", "gold_ore", "خام الذهب", "ذهب", "gold")) return "minecraft:gold_ore";
        if (containsAny(s, "copper ore", "copper_ore", "خام النحاس", "نحاس", "copper")) return "minecraft:copper_ore";
        if (containsAny(s, "coal ore", "coal_ore", "خام الفحم", "فحم", "coal")) return "minecraft:coal_ore";
        if (containsAny(s, "redstone ore", "redstone_ore", "خام ريدستون", "ريدستون")) return "minecraft:redstone_ore";
        if (containsAny(s, "lapis ore", "lapis_ore", "خام لازورد", "لازورد")) return "minecraft:lapis_ore";
        if (containsAny(s, "emerald ore", "emerald_ore", "خام الزمرد", "زمرد")) return "minecraft:emerald_ore";
        if (containsAny(s, "obsidian", "سبج")) return "minecraft:obsidian";
        if (containsAny(s, "cobblestone", "حصى حجري", "كوبلستون")) return "minecraft:cobblestone";
        if (containsAny(s, "gravel", "حصى", "زلط")) return "minecraft:gravel";
        if (containsAny(s, "sand", "رمل")) return "minecraft:sand";
        if (containsAny(s, "dirt", "تراب", "تربة")) return "minecraft:dirt";
        if (containsAny(s, "stone", "حجر")) return "minecraft:stone";
        if (containsAny(s, "oak log", "oak_log", "سجل بلوط")) return "minecraft:oak_log";
        return null;
    }

    private static String blockKeyword(String id) {
        if (id == null) return "stone";
        if (id.endsWith(":diamond_ore")) return "diamond_ore";
        if (id.endsWith(":iron_ore")) return "iron_ore";
        if (id.endsWith(":gold_ore")) return "gold_ore";
        if (id.endsWith(":copper_ore")) return "copper_ore";
        if (id.endsWith(":coal_ore")) return "coal_ore";
        if (id.endsWith(":redstone_ore")) return "redstone_ore";
        if (id.endsWith(":lapis_ore")) return "lapis_ore";
        if (id.endsWith(":emerald_ore")) return "emerald_ore";
        if (id.endsWith(":ancient_debris")) return "ancient_debris";
        return id.substring(id.indexOf(':') + 1);
    }

    private static boolean isOreId(String id) { return id != null && (id.contains("_ore") || id.endsWith(":ancient_debris")); }
    private static int parsedCount(String s, int fallback) {
        Matcher coordinates = COORDINATES.matcher(s);
        java.util.List<int[]> coordinateSpans = new java.util.ArrayList<>();
        while (coordinates.find()) coordinateSpans.add(new int[]{coordinates.start(), coordinates.end()});
        Matcher m = COUNT.matcher(s);
        while (m.find()) {
            boolean coordinateValue = false;
            for (int[] span : coordinateSpans) if (m.start() >= span[0] && m.end() <= span[1]) { coordinateValue = true; break; }
            if (coordinateValue) continue;
            try { return Math.max(1, Math.min(64, Integer.parseInt(m.group(1)))); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private static Route matchRule(String s, Rule r) {
        for (String key : r.keys()) {
            String k = normalize(key);
            if (k.isBlank()) continue;
            if (k.indexOf(' ') >= 0) {
                // Multi-word phrases are safe as substrings ("kill sheep" cannot hide in "skill").
                if (s.contains(k)) return new Route(r.tool(), r.arguments(), r.reason());
            } else if (k.codePoints().allMatch(cp -> (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9'))) {
                // Latin single-word keys need word boundaries: "till" must not match "still"
                // and "kill" must not match "skill".
                if (containsWord(s, k)) return new Route(r.tool(), r.arguments(), r.reason());
            } else if (s.contains(k)) {
                // Arabic keys stay substring-based so definite forms ("الخشب" contains "خشب") keep working.
                return new Route(r.tool(), r.arguments(), r.reason());
            }
        }
        return null;
    }

    private static int findPhrase(String text, String phrase) {
        int from = 0;
        while (from < text.length()) {
            int at = text.indexOf(phrase, from);
            if (at < 0) return -1;
            int end = at + phrase.length();
            boolean leftBoundary = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            boolean rightBoundary = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (leftBoundary && rightBoundary) return at;
            from = at + 1;
        }
        return -1;
    }

    private static boolean containsWord(String text, String word) {
        if (text == null || word == null || word.isBlank()) return false;
        Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(normalize(word)) + "(?![\\p{L}\\p{N}_])");
        return p.matcher(text).find();
    }

    /**
     * "كل"/"اكل" only mean "eat" when the imperative opens the sentence. Otherwise plain
     * requests such as "اجمع كل الخشب" (gather ALL the wood) or "ضع اكل في الصندوق" (put the
     * food in the chest) were routed to manage_autonomy eat.
     */
    private static boolean isEatIntent(String s) {
        if (containsAny(s, "eat food", "eat something", "have a meal", "تناول الطعام")) return true;
        if (containsWord(s, "eat") || containsWord(s, "تاكل") || containsWord(s, "اشبعني")) return true;
        return s.startsWith("كل ") || s.equals("كل") || s.startsWith("كلي ") || s.equals("كلي")
                || s.startsWith("اكل ") || s.equals("اكل") || s.startsWith("اكل، ") || s.startsWith("اكلي ");
    }

    private static boolean containsAny(String text, String... keys) {
        if (text == null) return false;
        for (String key : keys) {
            String k = normalize(key);
            if (!k.isBlank() && text.contains(k)) return true;
        }
        return false;
    }

    /** Unicode-safe normalization plus Arabic digit/letter variants; never transliterates meaning. */
    private static String normalize(String input) {
        if (input == null) return "";
        String s = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('\u0640', ' ')
                .replaceAll("[\\u064B-\\u065F\\u0670]", "")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي')
                .replace('\u0660', '0').replace('\u0661', '1').replace('\u0662', '2').replace('\u0663', '3')
                .replace('\u0664', '4').replace('\u0665', '5').replace('\u0666', '6').replace('\u0667', '7')
                .replace('\u0668', '8').replace('\u0669', '9');
        s = s.replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]", "");
        s = s.replaceAll("[!؟?؛;:]+", " ").replaceAll("\\s+", " ").trim();
        return s;
    }

    public static String unsupportedResponse(String request) {
        String normalized = normalize(request);
        if (normalized.equals("remember") || normalized.equals("تذكر")) {
            return containsArabic(request) ? "ماذا تريدني أن أتذكر؟ اكتب مثلًا: تذكر أنني أفضل الحديد." : "What should I remember? For example: remember that I prefer iron tools.";
        }
        if (normalized.equals("craft") || normalized.equals("make") || normalized.equals("create") || normalized.equals("اصنع") || normalized.equals("صنع")) {
            return containsArabic(request) ? "ما العنصر الذي تريدني أن أصنعه؟ اكتب اسمه والعدد إن أردت." : "What item should I craft? Include the item name and quantity, such as craft 4 torches.";
        }
        if (containsAny(normalized, "help", "what can you do", "ماذا تستطيع", "شنو تقدر", "ساعدني")) {
            return containsArabic(request)
                    ? "أستطيع اتباعك، والتحرك إلى الإحداثيات، وتعدين الكتل القريبة، وجمع الموارد، وبناء أشكال بسيطة، ومحاربة الأعداء القريبين، والبحث عن الهياكل، وفحص المخزون وإدارة الصناديق القريبة. أعطني أمرًا مباشرًا وحدد الهدف أو الإحداثيات عند الحاجة."
                    : "I can follow, move to coordinates, mine nearby blocks, gather resources, build simple patterns, fight nearby hostile mobs, search structures, inspect inventory and manage nearby containers. Use a direct command and include a target or coordinates when needed.";
        }
        if (containsArabic(request)) {
            return "لم أتمكن من ربط هذا الأمر بأداة محلية مؤكدة. جرّب أمرًا مباشرًا مثل: اتبعني، تعال هنا، استخرج الحديد، اجمع خشبًا، ابنِ جدارًا، ابحث عن قرية، أو اذهب إلى 100 64 -20.";
        }
        return "I couldn't map that request to a supported local action. Try a direct command such as follow me, come here, mine stone, gather wood, build a wall, find a village, or go to 100 64 -20.";
    }

    private static boolean containsArabic(String text) { return text != null && text.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF); }
    public static Route offline(String raw) { return first(raw); }
    private ToolSelectionRouter() {}
}
